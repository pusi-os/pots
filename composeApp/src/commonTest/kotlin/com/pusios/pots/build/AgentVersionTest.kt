package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The version shown in Settings is read from what the agent printed itself, so
 * these tests pin the two real formats the sandbox sees and the cases where the
 * honest answer is "not determined" rather than a number.
 */
class AgentVersionTest {

    @Test
    fun `reads the version out of Hermes' product line`() {
        assertEquals(
            "0.23.2",
            AgentVersion.parse("Hermes Agent v0.23.2 (2026.9.24) · stable · local ae2a81ccc00e"),
        )
    }

    @Test
    fun `reads the bare version Pi prints`() {
        assertEquals("1.5.2", AgentVersion.parse("pi 1.5.2"))
        assertEquals("1.5.2", AgentVersion.parse("1.5.2"))
        assertEquals("1.5.2", AgentVersion.parse("pi 1.5.2 (node v22.23.1)"))
    }

    @Test
    fun `takes the first version, which is the agent's own`() {
        // A bundled runtime's version follows the agent's and must not win.
        assertEquals("0.23.2", AgentVersion.parse("Hermes Agent v0.23.2 on node v22.23.1"))
    }

    @Test
    fun `a newline-delimited banner still yields one version`() {
        assertEquals("1.5.2", AgentVersion.parse("\n  Pi Installer\n\n1.5.2\n"))
    }

    @Test
    fun `nothing usable means null`() {
        assertNull(AgentVersion.parse(null))
        assertNull(AgentVersion.parse(""))
        assertNull(AgentVersion.parse("   \n"))
        assertNull(AgentVersion.parse("No models available"))
        assertNull(AgentVersion.parse("error: command not found: pi"))
        // Two-part and one-part numbers are not versions this card can show.
        assertNull(AgentVersion.parse("pi 1.5"))
    }
}
