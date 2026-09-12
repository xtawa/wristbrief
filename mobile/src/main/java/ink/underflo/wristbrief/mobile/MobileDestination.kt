package ink.underflo.wristbrief.mobile

enum class MobileDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
) {
    Today(route = "today", label = "Today", shortLabel = "T"),
    Explore(route = "explore", label = "Explore", shortLabel = "E"),
    Library(route = "library", label = "Library", shortLabel = "L"),
    NowPlaying(route = "now-playing", label = "Now Playing", shortLabel = "NP"),
    AiProvider(route = "ai-provider", label = "AI", shortLabel = "AI"),
}

fun initialMobileDestination(): MobileDestination = MobileDestination.Today
