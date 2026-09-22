package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import java.io.File

/**
 * Owns the on-disk wallpaper list.
 *
 * getWallpapers() is a synchronous binder call with a few-second timeout — overrunning it fails the
 * transaction silently and Projectivy shows a blank screen. So nothing here ever blocks on the
 * network: reads are served from cache, and a refresh runs in the background and announces itself
 * with ACTION_WALLPAPER_PROVIDER_UPDATED when it lands.
 */
object WallpaperRepository {

    private const val TAG = "WallpaperRepository"
    private const val CACHE_FILE = "wallpapers.json"
    private const val META_FILE = "compose_meta.json"

    /**
     * Projectivy holds a wallpaper list for itemsCacheDurationMillis, which outlives our own
     * refresh, so content:// URIs from an earlier list must still resolve. Keeping several
     * refreshes' worth of metadata around covers that overlap.
     */
    private const val META_RETAINED = 200

    /** Shorter than the manifest's itemsCacheDurationMillis so a refresh is usually already done. */
    private const val STALE_AFTER_MILLIS = 45L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)
    private fun metaFile(context: Context) = File(context.filesDir, META_FILE)

    private inline fun <reified T> readJson(file: File): T? = try {
        file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<T>(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable ${file.name}, ignoring", e)
        null
    }

    private inline fun <reified T> writeJson(file: File, value: T) {
        try {
            file.writeText(json.encodeToString(value))
        } catch (e: Exception) {
            Log.e(TAG, "Could not write ${file.name}", e)
        }
    }

    fun cachedWallpapers(context: Context): List<CachedWallpaper> {
        val cache = readJson<WallpaperCacheFile>(cacheFile(context)) ?: return emptyList()
        if (cache.configFingerprint != PreferencesManager.configFingerprint()) return emptyList()
        return cache.wallpapers
    }

    fun isStale(context: Context): Boolean {
        val cache = readJson<WallpaperCacheFile>(cacheFile(context)) ?: return true
        if (cache.configFingerprint != PreferencesManager.configFingerprint()) return true
        if (cache.wallpapers.isEmpty()) return true
        return System.currentTimeMillis() - cache.fetchedAtMillis > STALE_AFTER_MILLIS
    }

    /** Looked up by [WallpaperImageProvider] when it has to render an item. */
    fun composeMeta(context: Context, itemId: String): ComposeMeta? =
        readJson<ComposeMetaFile>(metaFile(context))?.entries?.firstOrNull { it.itemId == itemId }

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (!refreshLock.tryLock()) return@launch
            try {
                val count = refreshBlocking(appContext)
                if (count > 0) notifyProjectivy(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh failed", e)
            } finally {
                refreshLock.unlock()
            }
        }
    }

    /** Returns the number of wallpapers written. Caller must be off the main and binder threads. */
    fun refreshBlocking(context: Context): Int {
        PreferencesManager.init(context)
        if (!PreferencesManager.isConfigured) return 0

        val client = JellyfinClient(
            PreferencesManager.serverUrl,
            PreferencesManager.token,
            PreferencesManager.deviceId,
        )
        val config = PreferencesManager.queryConfig()
        val style = PreferencesManager.wallpaperStyle
        val limit = PreferencesManager.wallpaperLimit
        val width = if (PreferencesManager.fourK) 3840 else 1920
        val height = if (PreferencesManager.fourK) 2160 else 1080

        // /Items accepts a single parentId, so several libraries mean several queries.
        val items = if (config.parentIds.size > 1) {
            val perLibrary = (limit / config.parentIds.size).coerceAtLeast(5)
            config.parentIds.flatMap { parentId ->
                client.items(config.copy(parentIds = setOf(parentId)), perLibrary)
            }.shuffled()
        } else {
            client.items(config, limit)
        }

        val composed = style == WallpaperStyle.COMPOSED
        val metas = mutableListOf<ComposeMeta>()

        // Rendered images are keyed only by item id and frame size, so every other setting that
        // feeds into them has to drop the cache explicitly.
        val fingerprint = PreferencesManager.configFingerprint()
        if (readJson<WallpaperCacheFile>(cacheFile(context))?.configFingerprint != fingerprint) {
            WallpaperImageProvider.clear(context)
        }

        // The composer draws the backdrop into a box smaller than the frame, and never crops it.
        val artWidth = if (composed) WallpaperComposer.artSize(width) else width
        val artHeight = if (composed) WallpaperComposer.artSize(height) else height

        // Series share a backdrop with their episodes, so dedupe on the image rather than the item.
        val wallpapers = items
            .mapNotNull { item ->
                client.backdropUrl(item, artWidth, artHeight, fill = !composed)?.let { item to it }
            }
            .distinctBy { (_, backdrop) -> backdrop }
            .map { (item, backdrop) ->
                val title = item.seriesName?.takeIf { item.type == "Episode" } ?: item.name
                if (composed) {
                    metas += ComposeMeta(
                        itemId = item.id,
                        backdropUrl = backdrop,
                        logoUrl = client.logoUrl(item, WallpaperComposer.logoRequestWidth(width)),
                        title = title,
                        year = item.productionYear,
                        runtimeMinutes = item.runtimeMinutes,
                        communityRating = item.communityRating,
                        officialRating = item.officialRating,
                        genres = item.genres,
                        overview = item.overview,
                    )
                }
                CachedWallpaper(
                    uri = if (composed) WallpaperImageProvider.uriFor(context, item.id) else backdrop,
                    title = title,
                    subtitle = buildSubtitle(item),
                    itemId = item.id,
                )
            }

        if (composed) retainMetas(context, metas)

        writeJson(
            cacheFile(context),
            WallpaperCacheFile(
                fetchedAtMillis = System.currentTimeMillis(),
                configFingerprint = fingerprint,
                wallpapers = wallpapers,
            )
        )
        Log.i(TAG, "Cached ${wallpapers.size} wallpapers (${style.key})")
        return wallpapers.size
    }

    /** New entries win; older ones survive until they fall off the end. */
    private fun retainMetas(context: Context, fresh: List<ComposeMeta>) {
        val previous = readJson<ComposeMetaFile>(metaFile(context))?.entries ?: emptyList()
        val merged = (fresh + previous).distinctBy { it.itemId }.take(META_RETAINED)
        writeJson(metaFile(context), ComposeMetaFile(merged))
    }

    private fun buildSubtitle(item: Item): String? {
        val parts = buildList {
            item.genres.take(2).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
            item.productionYear?.let { add(it.toString()) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun notifyProjectivy(context: Context, reason: Int = WallpaperProviderContract.UpdateReason.DATA_CHANGED) {
        val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            `package` = PROJECTIVY_PACKAGE
            // The upstream sample passes the int resource id here; it must be the UUID string.
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, context.getString(R.string.plugin_uuid))
            putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, reason)
        }
        context.sendBroadcast(intent)
    }

    const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
}
