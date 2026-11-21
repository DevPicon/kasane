package dev.picon.androidhub.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import dev.picon.androidhub.core.SessionFailure

class MacEmulatorDiscoveryTest {
    @Test
    fun `only one hub process may own the stream and closing allows reconnect`() {
        val serial = "test-${System.nanoTime()}"
        val first = acquireSessionLock(serial)
        try {
            assertFailsWith<SessionFailure> { acquireSessionLock(serial) }
        } finally {
            first.channel().close()
        }
        acquireSessionLock(serial).channel().close()
    }

    @Test
    fun `token with equals and padded values is preserved`() {
        val endpoint = checkNotNull(parseEndpoint(listOf(
            "port.serial=5554",
            "grpc.port=8554",
            "avd.name=Medium Phone",
            "grpc.token=abc=def==",
        )))
        assertEquals("emulator-5554", endpoint.emulator.serial)
        assertEquals("Medium Phone", endpoint.emulator.name)
        assertEquals(8554, endpoint.port)
        assertEquals("abc=def==", endpoint.token)
    }

    @Test
    fun `invalid discovery does not produce a usable endpoint`() {
        assertNull(parseEndpoint(listOf("port.serial=5554")))
        assertNull(parseEndpoint(listOf("port.serial=5554", "grpc.port=70000")))
        assertNull(parseEndpoint(listOf("port.serial=0", "grpc.port=8554")))
    }
}
