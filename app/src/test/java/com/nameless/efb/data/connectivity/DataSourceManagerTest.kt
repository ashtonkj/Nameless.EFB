package com.nameless.efb.data.connectivity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourceManagerTest {

    private val prefs: DataStore<Preferences> = mockk(relaxed = true)

    // ── selectSource pure logic ───────────────────────────────────────────────

    @Test
    fun `selectSource prefers PLUGIN when fresh`() {
        val mgr = managerForPureTest()
        assertEquals(DataSource.PLUGIN, mgr.selectSource(0L, 0L, 0L))
    }

    @Test
    fun `selectSource falls back to GDL90 when plugin stale`() {
        val mgr = managerForPureTest()
        assertEquals(DataSource.GDL90, mgr.selectSource(600L, 100L, 100L))
    }

    @Test
    fun `selectSource falls back to UDP when plugin and GDL90 stale`() {
        val mgr = managerForPureTest()
        assertEquals(DataSource.UDP_BROADCAST, mgr.selectSource(600L, 600L, 100L))
    }

    @Test
    fun `selectSource returns NONE when all sources stale`() {
        val mgr = managerForPureTest()
        assertEquals(DataSource.NONE, mgr.selectSource(600L, 600L, 600L))
    }

    @Test
    fun `selectSource boundary 499ms is still alive`() {
        val mgr = managerForPureTest()
        assertEquals(DataSource.PLUGIN, mgr.selectSource(499L, 600L, 600L))
    }

    @Test
    fun `selectSource boundary 500ms is stale`() {
        val mgr = managerForPureTest()
        // 500ms is NOT < 500, so plugin is stale
        assertEquals(DataSource.GDL90, mgr.selectSource(500L, 100L, 100L))
    }

    // ── failover integration via time injection ───────────────────────────────

    @Test
    fun `failsOver to UDP when plugin is silent for 500ms`() = runTest {
        var fakeNow = 1_000L
        val mgr = DataSourceManager(
            scope = TestScope(testScheduler),
            prefs = prefs,
            timeSource = { fakeNow },
        )

        // Simulate plugin receiving a packet at t=1000
        mgr.pluginReceiver.lastPacketTime = fakeNow
        mgr.udpReceiver.lastPacketTime = fakeNow  // UDP also alive

        // Plugin age = 0 → PLUGIN is active
        assertEquals(DataSource.PLUGIN, mgr.selectSource(0L, Long.MAX_VALUE, 0L))

        // Plugin goes silent for 600ms; UDP stays alive (last packet was at t=1000)
        fakeNow = 1_600L

        val pluginAge = fakeNow - mgr.pluginReceiver.lastPacketTime   // 600ms
        val udpAge    = fakeNow - mgr.udpReceiver.lastPacketTime      // 600ms
        // If UDP had a recent packet:
        mgr.udpReceiver.lastPacketTime = 1_550L
        val udpAgeFresh = fakeNow - mgr.udpReceiver.lastPacketTime    // 50ms

        val selected = mgr.selectSource(pluginAge, Long.MAX_VALUE, udpAgeFresh)
        assertEquals(DataSource.UDP_BROADCAST, selected)
    }

    @Test
    fun `initial status is DISCONNECTED with NONE source`() = runTest {
        val mgr = managerForPureTest()
        assertEquals(DataSource.NONE, mgr.status.value.source)
        assertEquals(ConnectionStatus.State.DISCONNECTED, mgr.status.value.state)
    }

    @Test
    fun `start and stop do not throw`() = runTest {
        val mgr = DataSourceManager(
            scope = this,
            prefs = prefs,
        )
        mgr.start()
        mgr.stop()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun managerForPureTest() = DataSourceManager(
        scope = TestScope(),
        prefs = prefs,
    )
}
