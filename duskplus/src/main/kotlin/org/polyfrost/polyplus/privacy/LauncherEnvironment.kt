package org.polyfrost.polyplus.privacy

/** The Dusk launcher, which every build of this fork is made for. */
object LauncherEnvironment {
    private const val MARKER_PROPERTY = "polyplus.launcher"

    val isOneClient: Boolean get() = System.getProperty(MARKER_PROPERTY)?.trim()?.lowercase() != "standalone"

    /** No terms to accept: the launcher already covers Dusk's. */
    fun launcherAcceptedTerms(): Boolean? = null
}
