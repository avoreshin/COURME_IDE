package changelogai.feature.jenkins

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JenkinsChatBridgeTest {

    @BeforeEach
    fun setup() {
        JenkinsChatBridge.clearListeners()
    }

    @Test
    fun `addListener receives message from sendToChat`() {
        var received: String? = null
        JenkinsChatBridge.addListener { received = it }

        JenkinsChatBridge.sendToChat("Анализ сборки #42")

        assertEquals("Анализ сборки #42", received)
    }

    @Test
    fun `removeListener stops receiving messages`() {
        var callCount = 0
        val listener: (String) -> Unit = { callCount++ }
        JenkinsChatBridge.addListener(listener)
        JenkinsChatBridge.removeListener(listener)

        JenkinsChatBridge.sendToChat("test")

        assertEquals(0, callCount)
    }

    @Test
    fun `multiple listeners all receive the message`() {
        val received = mutableListOf<String>()
        JenkinsChatBridge.addListener { received.add("A: $it") }
        JenkinsChatBridge.addListener { received.add("B: $it") }

        JenkinsChatBridge.sendToChat("hello")

        assertEquals(2, received.size)
        assertTrue(received.any { it.startsWith("A:") })
        assertTrue(received.any { it.startsWith("B:") })
    }
}
