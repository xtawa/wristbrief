package ink.underflo.wristbrief.mobile

data class MobileFeedCategoryGroup(
    val category: String?,
    val feeds: List<MobileFeedSubscription>,
)

fun groupMobileFeedsByCategory(feeds: List<MobileFeedSubscription>): List<MobileFeedCategoryGroup> {
    if (feeds.isEmpty()) return emptyList()

    val normalized = feeds.map { feed -> feed.copy(category = normalizeFeedCategory(feed.category)) }
    val named = linkedMapOf<String, Pair<String, MutableList<MobileFeedSubscription>>>()
    val uncategorized = mutableListOf<MobileFeedSubscription>()

    normalized.forEach { feed ->
        val category = feed.category
        if (category == null) {
            uncategorized += feed
        } else {
            val key = category.lowercase()
            val entry = named.getOrPut(key) { category to mutableListOf() }
            entry.second += feed
        }
    }

    val namedGroups = named.values
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        .map { (category, groupedFeeds) ->
            MobileFeedCategoryGroup(
                category = category,
                feeds = groupedFeeds,
            )
        }

    return if (uncategorized.isEmpty()) {
        namedGroups
    } else {
        namedGroups + MobileFeedCategoryGroup(
            category = null,
            feeds = uncategorized,
        )
    }
}
