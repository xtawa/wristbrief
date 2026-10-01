package ink.underflo.wristbrief.mobile

/**
 * Where the app lands after onboarding, derived from the action the user chose.
 *
 * Previously the shell only reacted to AddFeed / ImportOpml; "Explore" and "Start
 * reading" were stored but ignored, so every Explore tap landed on Today. The shell
 * now applies this mapping once, when onboarding completes.
 *
 * @property destination the shell tab to select.
 * @property sourcesAction a Sources-screen action to run on arrival (opens the
 *   real feed editor or the OPML picker); null when nothing should open.
 */
internal data class OnboardingHandoff(
    val destination: MobileDestination,
    val sourcesAction: OnboardingAction?,
) {
    val opensSettings: Boolean get() = sourcesAction != null
}

internal fun resolveOnboardingHandoff(action: OnboardingAction): OnboardingHandoff = when (action) {
    OnboardingAction.AddFeed -> OnboardingHandoff(MobileDestination.Today, OnboardingAction.AddFeed)
    OnboardingAction.ImportOpml -> OnboardingHandoff(MobileDestination.Today, OnboardingAction.ImportOpml)
    OnboardingAction.Explore -> OnboardingHandoff(MobileDestination.Explore, null)
    OnboardingAction.StartReading -> OnboardingHandoff(MobileDestination.Today, null)
}
