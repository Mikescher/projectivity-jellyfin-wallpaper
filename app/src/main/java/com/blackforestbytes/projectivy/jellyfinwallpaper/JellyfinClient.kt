package com.blackforestbytes.projectivy.jellyfinwallpaper

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JellyfinException(message: String) : IOException(message)

/**
 * Minimal Jellyfin REST client.
 *
 * Only emits `Authorization: MediaBrowser ...`. Jellyfin 12 disables every legacy auth form
 * (X-Emby-Token, ?api_key=, scheme "Emby") by default, including on upgraded servers.
 */
class JellyfinClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val deviceId: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(): HttpUrl =
        baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw JellyfinException("Invalid server URL: $baseUrl")

    /**
     * Values are `Trim('"')`ed then URL-decoded server side, so every value must be quoted and
     * percent-encoded. A raw quote, comma or `=` would desync the parser.
     */
    private fun authHeader(withToken: Boolean): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val parts = mutableListOf(
            "Client=\"${enc(CLIENT_NAME)}\"",
            "Device=\"${enc(DEVICE_NAME)}\"",
            "DeviceId=\"${enc(deviceId)}\"",
            "Version=\"${enc(BuildConfig.VERSION_NAME)}\"",
        )
        if (withToken) parts += "Token=\"${enc(apiKey)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    private inline fun <reified T> get(url: HttpUrl, authenticated: Boolean = true): T {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(authenticated))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw JellyfinException("HTTP ${response.code} for ${url.encodedPath}")
            }
            return json.decodeFromString(body)
        }
    }

    fun systemInfo(): PublicSystemInfo =
        get(root().newBuilder().addPathSegments("System/Info/Public").build(), authenticated = false)

    fun users(): List<JellyfinUser> =
        get(root().newBuilder().addPathSegments("Users").build())

    /** Libraries visible to [userId]. `/Library/MediaFolders` is admin-only and 403s on a user token. */
    fun userViews(userId: String): List<Item> =
        get<ItemsResult>(
            root().newBuilder()
                .addPathSegments("UserViews")
                .addQueryParameter("userId", userId)
                .build()
        ).items

    fun filters(userId: String, parentIds: Set<String>, itemTypes: Set<String>): QueryFiltersLegacy {
        val b = root().newBuilder()
            .addPathSegments("Items/Filters")
            .addQueryParameter("userId", userId)
        if (itemTypes.isNotEmpty()) b.addQueryParameter("includeItemTypes", itemTypes.joinToString(","))
        // This endpoint takes a single parentId; with several libraries selected we merge per-library.
        if (parentIds.size == 1) b.addQueryParameter("parentId", parentIds.first())
        return get(b.build())
    }

    fun mergedFilters(userId: String, parentIds: Set<String>, itemTypes: Set<String>): QueryFiltersLegacy {
        if (parentIds.size <= 1) return filters(userId, parentIds, itemTypes)
        val genres = sortedSetOf<String>()
        val ratings = sortedSetOf<String>()
        val years = sortedSetOf<Int>()
        for (id in parentIds) {
            val f = filters(userId, setOf(id), itemTypes)
            genres += f.genres; ratings += f.officialRatings; years += f.years
        }
        return QueryFiltersLegacy(genres.toList(), ratings.toList(), years.toList())
    }

    fun items(config: QueryConfig, limit: Int): List<Item> {
        val b = root().newBuilder()
            .addPathSegments("Items")
            .addQueryParameter("userId", config.userId)
            .addQueryParameter("recursive", "true")
            .addQueryParameter("sortBy", config.sortMode.sortBy)
            .addQueryParameter("sortOrder", "Descending")
            .addQueryParameter("limit", limit.toString())
            // Filters to items that actually own a backdrop. There is no `hasImages` parameter.
            .addQueryParameter("imageTypes", "Backdrop")
            .addQueryParameter("enableTotalRecordCount", "false")
            // Everything else the composer needs is part of BaseItemDto already; Overview is not.
            .addQueryParameter(
                "fields",
                if (config.richMetadata) "Genres,ProductionYear,Overview" else "Genres,ProductionYear"
            )

        if (config.itemTypes.isNotEmpty()) {
            b.addQueryParameter("includeItemTypes", config.itemTypes.joinToString(","))
        }
        if (config.parentIds.size == 1) {
            b.addQueryParameter("parentId", config.parentIds.first())
        }
        // genres / officialRatings are PIPE-delimited; years / includeItemTypes are comma-delimited.
        if (config.genres.isNotEmpty()) b.addQueryParameter("genres", config.genres.joinToString("|"))
        if (config.officialRatings.isNotEmpty()) {
            b.addQueryParameter("officialRatings", config.officialRatings.joinToString("|"))
        }
        when (config.playedFilter) {
            PlayedFilter.ALL -> Unit
            PlayedFilter.UNPLAYED -> b.addQueryParameter("isPlayed", "false")
            PlayedFilter.PLAYED -> b.addQueryParameter("isPlayed", "true")
        }

        return get<ItemsResult>(b.build()).items
    }

    /**
     * Item image endpoints carry no [Authorize] attribute, so this URL needs no token and can be
     * handed straight to Projectivy, which fetches it in its own process.
     */
    fun backdropUrl(item: Item, width: Int, height: Int, fill: Boolean): String? {
        // Jellyfin only fills the Parent* fields when a parent backdrop really exists, so both
        // branches are backed by an image that is known to be there.
        val (id, tag) = when {
            item.backdropImageTags.isNotEmpty() -> item.id to item.backdropImageTags.first()
            item.parentBackdropItemId != null && item.parentBackdropImageTags.isNotEmpty() ->
                item.parentBackdropItemId to item.parentBackdropImageTags.first()
            else -> return null
        }
        return root().newBuilder()
            .addPathSegments("Items/$id/Images/Backdrop/0")
            // fill* crops to exactly this box; max* only bounds it and keeps the source aspect,
            // which is what the composer wants since it does its own letterboxing.
            .addQueryParameter(if (fill) "fillWidth" else "maxWidth", width.toString())
            .addQueryParameter(if (fill) "fillHeight" else "maxHeight", height.toString())
            .addQueryParameter("quality", "90")
            // The tag turns on a strong ETag plus immutable caching; without it caching is weak.
            .addQueryParameter("tag", tag)
            .build().toString()
    }

    /** Transparent title logo, when the item has one. */
    fun logoUrl(item: Item, maxWidth: Int): String? {
        val tag = item.imageTags["Logo"] ?: return null
        return root().newBuilder()
            .addPathSegments("Items/${item.id}/Images/Logo")
            .addQueryParameter("maxWidth", maxWidth.toString())
            .addQueryParameter("quality", "90")
            .addQueryParameter("tag", tag)
            .build().toString()
    }

    companion object {
        const val CLIENT_NAME = "Projectivy Jellyfin Wallpaper"
        const val DEVICE_NAME = "Android TV"
    }
}

data class QueryConfig(
    val userId: String,
    val parentIds: Set<String>,
    val itemTypes: Set<String>,
    val genres: Set<String>,
    val officialRatings: Set<String>,
    val playedFilter: PlayedFilter,
    val sortMode: SortMode = SortMode.RANDOM,
    val balanceTypes: Boolean = false,
    val richMetadata: Boolean = false,
)

/**
 * Downloads item images. Separate from [JellyfinClient] because these endpoints carry no
 * [Authorize] attribute, so the [WallpaperImageProvider] can use them without any credentials.
 */
object ImageFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun bytes(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.bytes() else null
        }
    }
}
