package com.pusios.pots.build

/**
 * The version an agent's own `--version` reports.
 *
 * Each CLI words its line differently — `pi 1.5.2`, `Hermes Agent v0.23.2
 * (2026.9.24) · stable · local ae2a81ccc00e` — so the only common part is the
 * dotted number, and the first one in the output is the agent's own (anything
 * later is a runtime it bundles). Nothing is assumed when it is absent: the
 * caller shows "not determined" rather than a guess.
 */
object AgentVersion {

    private val VERSION = Regex("""\bv?(\d+\.\d+\.\d+)""")

    /** [output]'s first `X.Y.Z` (an optional `v` prefix is not part of it), or null. */
    fun parse(output: String?): String? =
        output?.let { VERSION.find(it)?.groupValues?.get(1) }
}
