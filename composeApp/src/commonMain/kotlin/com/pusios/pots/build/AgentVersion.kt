package com.pusios.pots.build

/**
 * The version an agent's own `--version` reports.
 *
 * Each CLI words its line differently — `pi 1.5.2`, `Hermes Agent v0.23.2
 * (2026.9.24) · stable · local ae2a81ccc00e` — so the only common part is the
 * dotted number, and the first one in the output is the agent's own (anything
 * later is a runtime it bundles). Nothing is assumed when it is absent: the
 * caller shows "not determined" rather than a guess.
 *
 * Two things in that line are numbers but are not the version, and both have
 * been mistaken for one:
 * - the parenthesised release *date*. `__release_date__` in Hermes is a constant
 *   the upstream does not bump, so it says `2026.9.24` on a November build, and
 *   compared against `0.23.3` it reads as "ahead of the channel" — "up to date"
 *   on a build that is not;
 * - `vgit.<sha>`, which is Hermes saying "no release tag is reachable from this
 *   checkout". That is not a version at all, and the number after it is the date
 *   above.
 */
object AgentVersion {

    private val VERSION = Regex("""\bv?(\d+\.\d+\.\d+)""")

    /** A parenthesised group — where Hermes puts its release date. */
    private val PARENTHESISED = Regex("""\([^)]*\)""")

    /** Hermes' stand-in for a version when git finds no release tag: `git.<sha>`. */
    private val GIT_DESCRIBED = Regex("""git\.[0-9a-f]{4,}""")

    /**
     * [output]'s first `X.Y.Z` (an optional `v` prefix is not part of it), or
     * null — including when the line carries no version at all.
     */
    fun parse(output: String?): String? {
        val line = output?.replace(PARENTHESISED, " ") ?: return null
        if (GIT_DESCRIBED.containsMatchIn(line)) return null
        return VERSION.find(line)?.groupValues?.get(1)
    }
}
