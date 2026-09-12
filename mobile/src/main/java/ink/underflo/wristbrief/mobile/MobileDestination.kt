package ink.underflo.wristbrief.mobile

/**
 * Phone navigation per uidocs: Home / Explore / Ask AI / Library as the four
 * primary tabs; playback lives in the mini/expanded player surfaces (not a
 * fifth tab) and Settings is reached from the top-right action. Enum names are
 * stable because they persist as the selected-tab key.
 */
enum class MobileDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
    val showsInBottomBar: Boolean,
) {
    Today(route = "today", label = "Home", shortLabel = "H", showsInBottomBar = true),
    Explore(route = "explore", label = "Explore", shortLabel = "E", showsInBottomBar = true),
    AiProvider(route = "ai-provider", label = "Ask AI", shortLabel = "AI", showsInBottomBar = true),
    Library(route = "library", label = "Library", shortLabel = "L", showsInBottomBar = true),
    NowPlaying(route = "now-playing", label = "Now Playing", shortLabel = "NP", showsInBottomBar = false),
}

fun initialMobileDestination(): MobileDestination = MobileDestination.Today
