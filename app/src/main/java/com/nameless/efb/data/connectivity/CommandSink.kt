package com.nameless.efb.data.connectivity

/**
 * Abstraction over the command channel to the X-Plane plugin.
 *
 * Implementations encode [json] as a UTF-8 UDP datagram and deliver it to the
 * plugin command port (49101).  In unit tests, this interface is mocked with
 * MockK so that command payloads can be verified without network I/O.
 *
 * Command format (JSON):
 * ```json
 * {"cmd":"set_dataref","path":"sim/cockpit/...","value":1.0}
 * {"cmd":"swap_freq","radio":"COM1"}
 * {"cmd":"set_standby_freq","radio":"COM1","hz":118125000}
 * ```
 */
interface CommandSink {
    /**
     * Sends a JSON-encoded command to the X-Plane plugin.
     *
     * @param json  UTF-8 JSON string. Must not exceed 1024 bytes.
     */
    fun sendCommand(json: String)
}
