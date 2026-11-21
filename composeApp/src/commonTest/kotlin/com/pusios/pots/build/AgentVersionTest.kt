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
            "0.23.3",
            AgentVersion.parse("Hermes Agent v0.23.3 (2026.9.24) · stable · local caafe5389ae5"),
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
        assertEquals("0.23.3", AgentVersion.parse("Hermes Agent v0.23.3 on node v22.23.1"))
    }

    @Test
    fun `Hermes' git-described line has no version, and its release date is not one`() {
        // What a checkout with no reachable release tag prints. `2026.9.24` is
        // `__release_date__`, a constant the upstream stopped bumping in
        // September — reading it as the version is what made a November build
        // compare as newer than the release it was behind.
        assertNull(
            AgentVersion.parse("Hermes Agent vgit.ae2a81c (2026.9.24) · stable · local ae2a81ccc00e"),
        )
        // The same line once git can name the release: the version is before the
        // date, and the date is dropped rather than considered.
        assertEquals("0.23.2", AgentVersion.parse("Hermes Agent v0.23.2 (2026.9.24) · stable"))
        assertEquals("1.5.2", AgentVersion.parse("pi 1.5.2"))
        // A date in brackets is never the answer, even with no version in front of it.
        assertNull(AgentVersion.parse("Hermes Agent vgit.ae2a81c (2026.9.24)"))
        // A developer build's `+N.g<sha>` suffix is still a version.
        assertEquals("0.21.5", AgentVersion.parse("Hermes Agent v0.21.5+912.gdeadbee (2026.9.24) · main"))
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
