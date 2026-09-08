package ink.underflo.wristbrief.mobile

enum class MobileDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
) {
    Feeds(route = "feeds", label = "Feeds", shortLabel = "F"),
    AiProvider(route = "ai-provider", label = "AI", shortLabel = "AI"),
    Membership(route = "membership", label = "Membership", shortLabel = "M"),
}

fun initialMobileDestination(): MobileDestination = MobileDestination.Feeds
