package ink.underflo.wristbrief.mobile

enum class MobileDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
) {
    Today(route = "today", label = "Today", shortLabel = "T"),
    Library(route = "library", label = "Library", shortLabel = "L"),
    AiProvider(route = "ai-provider", label = "AI", shortLabel = "AI"),
}

fun initialMobileDestination(): MobileDestination = MobileDestination.Today
