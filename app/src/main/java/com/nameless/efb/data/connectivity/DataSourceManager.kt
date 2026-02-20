package com.nameless.efb.data.connectivity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates all data sources and emits a unified [SimSnapshot] stream.
 *
 * Priority order: PLUGIN > GDL90 > UDP_BROADCAST.
 *
 * Health monitor polls every 100ms; if the active source has been silent for
 * more than 500ms the next-lower source is promoted automatically (XC-06).
 *
 * @param timeSource Injectable clock for unit testing; defaults to wall clock.
 */
class DataSourceManager(
    private val scope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") private val prefs: DataStore<Preferences>,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) : CommandSink {
    private val _simData = MutableStateFlow<SimSnapshot?>(null)
    val simData: StateFlow<SimSnapshot?> = _simData.asStateFlow()

    private val _status = MutableStateFlow(
        ConnectionStatus(
            state = ConnectionStatus.State.DISCONNECTED,
            source = DataSource.NONE,
            latencyMs = 0,
            lastDataAge = Long.MAX_VALUE,
        )
    )
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    @Volatile private var activeSource: DataSource = DataSource.NONE

    internal val pluginReceiver = PluginReceiver(
        scope = scope,
        onSnapshot = { onPacketReceived(DataSource.PLUGIN, it) },
        onStatusChange = { /* future: track plugin connect/disconnect */ },
    )

    internal val gdl90Receiver = Gdl90Receiver(
        scope = scope,
        onSnapshot = { onPacketReceived(DataSource.GDL90, it) },
    )

    internal val udpReceiver = UdpBroadcastReceiver(
        scope = scope,
        onSnapshot = { onPacketReceived(DataSource.UDP_BROADCAST, it) },
    )

    private var healthJob: Job? = null

    fun start(xplaneAddress: java.net.InetAddress? = null, port: Int = 49100) {
        pluginReceiver.start()
        gdl90Receiver.start()
        udpReceiver.start()
        healthJob = scope.launch { monitorSourceHealth() }
    }

    /**
     * Sends a JSON command to the X-Plane plugin over the plugin UDP channel.
     *
     * The datagram is sent to the last known plugin address (populated once
     * the first data packet is received from X-Plane).  Safe to call from any thread.
     */
    override fun sendCommand(json: String) {
        pluginReceiver.sendCommand(json)
    }

    fun stop() {
        healthJob?.cancel()
        pluginReceiver.stop()
        gdl90Receiver.stop()
        udpReceiver.stop()
        activeSource = DataSource.NONE
    }

    private fun onPacketReceived(source: DataSource, snapshot: SimSnapshot) {
        if (source.priority >= activeSource.priority) {
            activeSource = source
            _simData.value = snapshot
        }
    }

    private suspend fun monitorSourceHealth() {
        while (true) {
            delay(100)
            val now = timeSource()
            val pluginAge = now - pluginReceiver.lastPacketTime
            val gdl90Age  = now - gdl90Receiver.lastPacketTime
            val udpAge    = now - udpReceiver.lastPacketTime

            activeSource = selectSource(pluginAge, gdl90Age, udpAge)
            _status.value = buildConnectionStatus(now)
        }
    }

    /** Pure helper exposed for direct unit testing of failover logic. */
    internal fun selectSource(pluginAge: Long, gdl90Age: Long, udpAge: Long): DataSource = when {
        pluginAge < 500  -> DataSource.PLUGIN
        gdl90Age  < 500  -> DataSource.GDL90
        udpAge    < 500  -> DataSource.UDP_BROADCAST
        else             -> DataSource.NONE
    }

    private fun buildConnectionStatus(now: Long): ConnectionStatus {
        val lastAge = when (activeSource) {
            DataSource.PLUGIN        -> now - pluginReceiver.lastPacketTime
            DataSource.GDL90         -> now - gdl90Receiver.lastPacketTime
            DataSource.UDP_BROADCAST -> now - udpReceiver.lastPacketTime
            DataSource.NONE          -> Long.MAX_VALUE
        }
        val state = when {
            activeSource != DataSource.NONE -> ConnectionStatus.State.CONNECTED
            lastAge < 5000                  -> ConnectionStatus.State.RECONNECTING
            else                            -> ConnectionStatus.State.DISCONNECTED
        }
        return ConnectionStatus(
            state = state,
            source = activeSource,
            latencyMs = lastAge.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            lastDataAge = lastAge,
        )
    }
}

data class ConnectionStatus(
    val state: State,
    val source: DataSource,
    val latencyMs: Int,
    val lastDataAge: Long,
) {
    enum class State { CONNECTED, RECONNECTING, DISCONNECTED }
}

enum class DataSource(val priority: Int) {
    NONE(0), UDP_BROADCAST(1), GDL90(2), PLUGIN(3)
}
