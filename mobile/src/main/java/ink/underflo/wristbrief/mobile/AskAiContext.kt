package ink.underflo.wristbrief.mobile

/**
 * The content that is actually sent with an Ask AI request. Built once per
 * request so the screen can show the real number of grounding items and cite
 * only the items that were part of the prompt, instead of a fixed slice of the
 * whole inbox.
 *
 * @property items the inbox items whose title/description were included; empty
 *   when the request was grounded in a prefilled article/clip or in the bare query.
 * @property content the text sent as the summary body.
 * @property usesPrefilledContent true when the request used the article/clip
 *   that was handed over from the reader or the player.
 */
data class AskAiContext(
    val items: List<MobileFeedItem>,
    val content: String,
    val usesPrefilledContent: Boolean,
) {
    val sourceCount: Int get() = if (usesPrefilledContent) 1 else items.size
}

object AskAiContextBuilder {
    /** Matches [DailyBriefInputBuilder]'s per-request article limit. */
    const val MAX_ITEMS = 6

    /**
     * Items eligible for the "Today's Brief" scope: the same set the Today hero
     * uses for generation (unread first, the whole inbox once everything is read).
     */
    fun briefEligibleItems(allItems: List<MobileFeedItem>, unreadItems: List<MobileFeedItem>): List<MobileFeedItem> =
        unreadItems.ifEmpty { allItems }

    fun itemsForScope(
        scope: AskAiScope,
        allItems: List<MobileFeedItem>,
        unreadItems: List<MobileFeedItem>,
        savedItems: List<MobileFeedItem>,
    ): List<MobileFeedItem> = when (scope) {
        AskAiScope.All -> allItems
        AskAiScope.Today -> briefEligibleItems(allItems, unreadItems)
        AskAiScope.Unread -> unreadItems
        AskAiScope.Saved -> savedItems
        AskAiScope.Article -> emptyList()
    }

    fun build(
        scope: AskAiScope,
        query: String,
        allItems: List<MobileFeedItem>,
        unreadItems: List<MobileFeedItem>,
        savedItems: List<MobileFeedItem>,
        prefilledContent: String,
    ): AskAiContext {
        if (scope == AskAiScope.Article && prefilledContent.isNotBlank()) {
            return AskAiContext(items = emptyList(), content = prefilledContent, usesPrefilledContent = true)
        }
        val candidates = itemsForScope(scope, allItems, unreadItems, savedItems).take(MAX_ITEMS)
        if (candidates.isEmpty()) {
            return AskAiContext(items = emptyList(), content = query, usesPrefilledContent = false)
        }
        val content = candidates.joinToString("\n\n") { item ->
            val excerpt = ArticleContentSanitizer.sanitize(item.description).plainText.take(1500).trim()
            buildString {
                append("Source: ").append(item.feedTitle.ifBlank { "Feed" }).append('\n')
                append("Title: ").append(item.title)
                if (excerpt.isNotBlank()) append('\n').append("Summary: ").append(excerpt)
            }
        }
        return AskAiContext(items = candidates, content = content, usesPrefilledContent = false)
    }
}
