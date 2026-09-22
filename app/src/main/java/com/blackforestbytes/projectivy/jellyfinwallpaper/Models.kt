package com.blackforestbytes.projectivy.jellyfinwallpaper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<Item> = emptyList(),
)

@Serializable
data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Overview") val overview: String? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
) {
    /** One tick is 100ns. */
    val runtimeMinutes: Int?
        get() = runTimeTicks?.takeIf { it > 0 }?.let { (it / 10_000_000L / 60L).toInt() }
}

/** `GET /Items/Filters` — the legacy shape, the only one that also returns ratings and years. */
@Serializable
data class QueryFiltersLegacy(
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("OfficialRatings") val officialRatings: List<String> = emptyList(),
    @SerialName("Years") val years: List<Int> = emptyList(),
)

/** Tri-state for Jellyfin's `isPlayed` query parameter; ALL omits it. */
enum class PlayedFilter(val key: String) {
    ALL("all"),
    UNPLAYED("unplayed"),
    PLAYED("played");

    companion object {
        fun from(key: String?): PlayedFilter? = entries.firstOrNull { it.key == key }
    }
}

/** How a wallpaper image is produced. */
enum class WallpaperStyle(val key: String) {
    /** Hand Projectivy the Jellyfin backdrop URL and let it draw its own title overlay. */
    BACKDROP("backdrop"),

    /** Render backdrop, logo and metadata into one image, served over a `content://` URI. */
    COMPOSED("composed");

    companion object {
        fun from(key: String?): WallpaperStyle = entries.firstOrNull { it.key == key } ?: BACKDROP
    }
}

enum class SortMode(val key: String, val sortBy: String) {
    RANDOM("random", "Random"),
    RECENT("recent", "DateCreated");

    companion object {
        fun from(key: String?): SortMode = entries.firstOrNull { it.key == key } ?: RANDOM
    }
}

/** One entry of the on-disk wallpaper cache. */
@Serializable
data class CachedWallpaper(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val itemId: String,
)

@Serializable
data class WallpaperCacheFile(
    val fetchedAtMillis: Long,
    val configFingerprint: String,
    val wallpapers: List<CachedWallpaper>,
)

/**
 * Everything [WallpaperComposer] needs for one item.
 *
 * Kept in its own store rather than in [WallpaperCacheFile], because Projectivy holds wallpaper
 * URIs for longer than we keep a list, and a `content://` URI has to stay resolvable until it does.
 */
@Serializable
data class ComposeMeta(
    val itemId: String,
    val backdropUrl: String,
    val logoUrl: String? = null,
    val title: String,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val communityRating: Double? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
)

@Serializable
data class ComposeMetaFile(
    val entries: List<ComposeMeta> = emptyList(),
)
