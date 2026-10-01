package ink.underflo.wristbrief.mobile

import java.net.URI

/**
 * Pure catalog logic for Explore: which category chips to show and which curated
 * feeds match the current query. Chips are derived from the curated catalog, so an
 * empty category is never offered (see PRODUCT_DESIGN_CONSTRAINTS: show only
 * categories that have content).
 */
object ExploreCatalog {
    const val ALL = "All"
    const val PODCASTS = "Podcasts"

    /** Canonical category key for a curated feed ("Tech" and "Technology" collapse). */
    fun canonicalCategory(category: String): String = when (category.trim().lowercase()) {
        "tech", "technology" -> "Technology"
        else -> category.trim()
    }

    /** `All`, then the catalog's own categories in first-seen order, then `Podcasts` when any podcast exists. */
    fun categories(feeds: List<SampleFeed>): List<String> {
        val own = feeds.map { canonicalCategory(it.category) }
            .filter { it.isNotBlank() && !it.equals(ALL, true) && !it.equals(PODCASTS, true) }
            .distinct()
        val podcasts = listOfNotNull(PODCASTS.takeIf { feeds.any { it.isPodcast } })
        return listOf(ALL) + own + podcasts
    }

    fun matches(feed: SampleFeed, query: String, category: String?): Boolean {
        val matchesCategory = category == null ||
            category.equals(ALL, ignoreCase = true) ||
            (category.equals(PODCASTS, ignoreCase = true) && feed.isPodcast) ||
            canonicalCategory(feed.category).equals(canonicalCategory(category), ignoreCase = true)
        val q = query.trim()
        val matchesSearch = q.isBlank() ||
            feed.title.contains(q, ignoreCase = true) ||
            feed.description.contains(q, ignoreCase = true) ||
            feed.category.contains(q, ignoreCase = true) ||
            host(feed.url).contains(q, ignoreCase = true)
        return matchesCategory && matchesSearch
    }

    fun filter(feeds: List<SampleFeed>, query: String, category: String?): List<SampleFeed> =
        feeds.filter { matches(it, query, category) }

    /** Real, derivable metadata for a card: the publisher host, without a `www.` prefix. */
    fun host(url: String): String = runCatching { URI(url).host.orEmpty() }
        .getOrDefault("")
        .removePrefix("www.")
}
