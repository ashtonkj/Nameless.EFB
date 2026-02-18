# Plan 05 — Connectivity Layer

**Phase:** 3
**Depends on:** Plans 01, 02 (Rust plugin protocol defined)
**Blocks:** Plans 06–13 (all display plans need live dataref flow)

---

## Goals

Implement the `DataSourceManager` abstraction that feeds all three display modes with sim data:
- Primary: Rust plugin binary UDP stream (port 49100)
- Secondary: GDL-90 multicast/broadcast
- Fallback: X-Plane native UDP broadcast (port 49000)
- BECN auto-discovery (port 49707)
- Connection health UI indicator (XC-05)
- Hybrid protocol with 500ms failover (XC-06)

Requirements covered: XC-01 through XC-06, UI-08.

---

## 1. DataSourceManager

```kotlin
// data/connectivity/DataSourceManager.kt

class DataSourceManager(
    private val scope: CoroutineScope,
    private val prefs: DataStore<Preferences>,
) {
    // Emits the latest sim snapshot at up to 20Hz
    val simData: StateFlow<SimSnapshot?> = _simData.asStateFlow()

    // Current connection status
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _simData = MutableStateFlow<SimSnapshot?>(null)
    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)

    // Priority order: PLUGIN > GDL90 > UDP_BROADCAST
    private var activeSource: DataSource = DataSource.NONE

    fun start(xplaneAddress: InetAddress?, port: Int = 49100) { ... }
    fun stop() { ... }

    private fun onPacketReceived(source: DataSource, snapshot: SimSnapshot) {
        if (source.priority >= activeSource.priority) {
            activeSource = source
            _simData.value = snapshot
        }
    }

    // Failover: if PLUGIN stream is lost for > 500ms, drop to next source
    private fun monitorSourceHealth() { ... }
}

data class ConnectionStatus(
    val state: State,
    val source: DataSource,
    val latencyMs: Int,
    val lastDataAge: Long,        // ms since last packet
) {
    enum class State { CONNECTED, RECONNECTING, DISCONNECTED }
}

enum class DataSource(val priority: Int) {
    NONE(0), UDP_BROADCAST(1), GDL90(2), PLUGIN(3)
}
```

---

## 2. Rust Plugin Receiver (XC-01)

```kotlin
// data/connectivity/PluginReceiver.kt

class PluginReceiver(
    private val port: Int = 49100,
    private val onSnapshot: (SimSnapshot) -> Unit,
    private val onStatusChange: (Boolean) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private val buffer = ByteArray(8192)

    fun start() {
        scope.launch(Dispatchers.IO) {
            socket = DatagramSocket(port)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                socket?.receive(packet)
                val snapshot = parsePluginPacket(packet.data, packet.length)
                    ?: continue  // drop malformed
                sendAck(packet.address, packet.port)  // ACK for watchdog
                onSnapshot(snapshot)
            }
        }
    }

    private fun parsePluginPacket(data: ByteArray, len: Int): SimSnapshot? {
        // Validate magic, version, checksum (mirrors efb-protocol Rust logic in Kotlin)
        // Deserialize SimSnapshot fields
    }

    private fun sendAck(addr: InetAddress, port: Int) {
        // Send PacketType::Ack datagram
    }
}
```

### Kotlin protocol decoder
Mirror the `efb-protocol` Rust encoding in Kotlin so the app can decode without FFI:
```kotlin
object EfbProtocol {
    const val MAGIC = 0xEFB12345L
    const val VERSION = 1

    fun decode(buf: ByteArray, len: Int): SimSnapshot? {
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int.toLong() and 0xFFFFFFFFL
        if (magic != MAGIC) return null
        val version = bb.short.toInt()
        if (version != VERSION) return null
        val packetType = bb.get().toInt()
        val payloadLen = bb.short.toInt()
        val sequence = bb.int
        val checksum = bb.int
        // Verify CRC32 of payload
        val payload = ByteArray(payloadLen).also { bb.get(it) }
        if (crc32(payload) != checksum) return null
        return deserializeSnapshot(payload)
    }
}
```

---

## 3. GDL-90 Receiver (XC-03)

GDL-90 is an ADS-B data format used by X-Plane 12.3+.

```kotlin
// data/connectivity/Gdl90Receiver.kt

class Gdl90Receiver(
    private val onSnapshot: (SimSnapshot) -> Unit,
) {
    // X-Plane sends GDL-90 on 239.253.1.1 multicast or broadcast
    // Messages used: MSG_ID 0x0A (OWNSHIP REPORT), 0x65 (AHRS)

    fun start() {
        scope.launch(Dispatchers.IO) {
            val socket = MulticastSocket(4000)  // GDL-90 standard port
            socket.joinGroup(InetAddress.getByName("239.253.1.1"))
            // Receive loop, parse GDL-90 frames
        }
    }

    private fun parseGdl90Frame(data: ByteArray): SimSnapshot? {
        // GDL-90 framing: 0x7E start flag, HDLC bit stuffing, 0x7E end flag
        // MSG_ID 0x0A: lat(3), lon(3), alt(2), misc, navIntegrity, navAccuracy, horiz_velocity(2), vert_velocity(2), track(2), emitter_cat, callsign(8)
        // MSG_ID 0x65 (AHRS extension): pitch(2), roll(2), heading(2)
    }
}
```

---

## 4. UDP Broadcast Fallback (XC-02)

```kotlin
// data/connectivity/UdpBroadcastReceiver.kt

class UdpBroadcastReceiver(
    private val port: Int = 49000,
    private val onSnapshot: (SimSnapshot) -> Unit,
) {
    // X-Plane native UDP output format: 5-byte header "DATA\0", then
    // packets of 36 bytes each (1 int index + 8 floats)
    // Group 3 = Speed, Group 6 = Attitude, Group 17 = Position

    private fun parseXplaneUdp(data: ByteArray): SimSnapshot? {
        if (!data.startsWith("DATA")) return null
        val groups = mutableMapOf<Int, FloatArray>()
        var offset = 5
        while (offset + 36 <= data.size) {
            val groupId = ByteBuffer.wrap(data, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val floats = FloatArray(8)
            ByteBuffer.wrap(data, offset + 4, 32)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(floats)
            groups[groupId] = floats
            offset += 36
        }
        // Map groups to SimSnapshot fields
        return buildSnapshot(groups)
    }
}
```

---

## 5. BECN Auto-Discovery (XC-04)

```kotlin
// data/connectivity/BecnDiscovery.kt

class BecnDiscovery(
    private val wifiManager: WifiManager,
    private val onFound: (XplaneInstance) -> Unit,
) {
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        multicastLock = wifiManager.createMulticastLock("efb_becn")
        multicastLock?.acquire()

        scope.launch(Dispatchers.IO) {
            val socket = MulticastSocket(49707)
            socket.joinGroup(InetAddress.getByName("239.255.1.1"))
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            withTimeoutOrNull(5_000) {
                while (isActive) {
                    socket.receive(packet)
                    val instance = parseBecn(packet)
                    if (instance != null) onFound(instance)
                }
            }
        }
    }

    fun stop() {
        multicastLock?.release()
    }

    private fun parseBecn(packet: DatagramPacket): XplaneInstance? {
        // X-Plane BECN format: "BECN\0" + computer_name (null-terminated) + IP + port
        val data = packet.data
        if (!data.startsWith("BECN")) return null
        return XplaneInstance(
            address = packet.address,
            port = 49100,
            name = extractString(data, 5),
        )
    }
}

data class XplaneInstance(val address: InetAddress, val port: Int, val name: String)
```

---

## 6. Connection Status Indicator (XC-05)

The status indicator appears in all three display modes as a small overlay at the top-right corner (does not obscure instruments). Rendered as a Compose overlay over the GL views.

```kotlin
// ui/connectivity/ConnectionStatusBadge.kt

@Composable
fun ConnectionStatusBadge(status: ConnectionStatus) {
    val color = when (status.state) {
        CONNECTED    -> Color(0xFF00C800)  // green
        RECONNECTING -> Color(0xFFFFA500)  // amber
        DISCONNECTED -> Color(0xFFFF0000)  // red
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
    // Long-press shows: source name, latency, last data age
}
```

### Auto-reconnect with exponential backoff
```kotlin
private fun scheduleReconnect() {
    val delays = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    var attempt = 0
    scope.launch {
        while (!isConnected) {
            delay(delays.getOrElse(attempt) { 30_000L })
            tryConnect()
            attempt++
        }
    }
}
```

---

## 7. Hybrid Protocol Architecture (XC-06)

```kotlin
// DataSourceManager: source priority and failover

private fun monitorSourceHealth() {
    scope.launch {
        while (isActive) {
            delay(100)  // check every 100ms
            val now = System.currentTimeMillis()
            val pluginAge = now - pluginReceiver.lastPacketTime
            val gdl90Age  = now - gdl90Receiver.lastPacketTime
            val udpAge    = now - udpReceiver.lastPacketTime

            activeSource = when {
                pluginAge < 500  -> DataSource.PLUGIN
                gdl90Age  < 500  -> DataSource.GDL90
                udpAge    < 500  -> DataSource.UDP_BROADCAST
                else             -> DataSource.NONE
            }
            _status.value = buildConnectionStatus()
        }
    }
}
```

---

## 8. Unit Tests

```kotlin
// JVM unit tests (no networking, mock sockets)

@Test
fun pluginPacket_validDecodes() {
    val snapshot = SimSnapshot(latitude = -26.1392, longitude = 28.2462, ...)
    val encoded = EfbProtocol.encode(snapshot)
    val decoded = EfbProtocol.decode(encoded, encoded.size)
    assertNotNull(decoded)
    assertEquals(snapshot.latitude, decoded!!.latitude, 0.000001)
}

@Test
fun pluginPacket_wrongMagicReturnsNull() {
    val buf = EfbProtocol.encode(testSnapshot())
    buf[0] = 0xFF.toByte()  // corrupt magic
    assertNull(EfbProtocol.decode(buf, buf.size))
}

@Test
fun pluginPacket_badChecksumReturnsNull() { ... }

@Test
fun udpBroadcast_group3Parses() {
    val xplanePacket = buildXplaneUdpGroup3(speed_kts = 110f)
    val snapshot = UdpBroadcastReceiver.parseXplaneUdp(xplanePacket)
    assertNotNull(snapshot)
    assertEquals(110f, snapshot!!.ias_kts, 0.5f)
}

@Test
fun dataSourceManager_failsOverToUdpWhenPluginSilent() {
    // Feed plugin packets for 1s, then stop
    // After 500ms of silence, source should switch to UDP
}
```

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| XC-01 (plugin primary channel) | `PluginReceiver`, `EfbProtocol.decode` |
| XC-02 (UDP fallback within 3s) | `UdpBroadcastReceiver` + failover monitor |
| XC-03 (GDL-90 support) | `Gdl90Receiver` |
| XC-04 (BECN auto-discovery within 5s) | `BecnDiscovery` with 5s timeout |
| XC-05 (status indicator) | `ConnectionStatusBadge` |
| XC-06 (hybrid failover 500ms) | `monitorSourceHealth()` 100ms poll |
| UI-08 (offline-first) | All map/nav data from Room/files; network only for METAR/Navigraph |
| NFR-R03 (stale data indicator within 2s) | `lastDataAge` in status + 5s staleness overlay |
