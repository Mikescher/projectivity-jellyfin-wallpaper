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
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
)

/** `GET /Items/Filters` — the legacy shape, the only one that also returns ratings and years. */
@Serializable
data class QueryFiltersLegacy(
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("OfficialRatings") val officialRatings: List<String> = emptyList(),
    @SerialName("Years") val years: List<Int> = emptyList(),
)

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
