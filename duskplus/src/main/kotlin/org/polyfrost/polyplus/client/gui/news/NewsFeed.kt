package org.polyfrost.polyplus.client.gui.news

import org.polyfrost.polyplus.client.utils.ClientPlatform

internal class NewsPost(
    val title: String,
    val summary: String,
    val link: String,
    val published: Long,
    val image: String?,
)

/** Dusk has no news feed, so the tab stays hidden ([enabled]). */
internal object NewsFeed {
    val posts: List<NewsPost> = emptyList()
    const val loading = false
    const val loaded = true
    const val failed = false
    const val enabled = false
    const val unread = false

    fun refresh(force: Boolean = false) = Unit

    fun markSeen() = Unit

    fun open(post: NewsPost) = ClientPlatform.openUri(post.link)

    fun openBlog() = Unit
}
