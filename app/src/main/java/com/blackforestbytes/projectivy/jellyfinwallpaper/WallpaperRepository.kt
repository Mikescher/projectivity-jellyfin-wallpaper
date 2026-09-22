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
    private const val ITEM_LIMIT = 40

    /** Shorter than the manifest's itemsCacheDurationMillis so a refresh is usually already done. */
    private const val STALE_AFTER_MILLIS = 45L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun read(context: Context): WallpaperCacheFile? = try {
        cacheFile(context).takeIf { it.exists() }
            ?.readText()
            ?.let { json.decodeFromString<WallpaperCacheFile>(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable cache, ignoring", e)
        null
    }

    private fun write(context: Context, value: WallpaperCacheFile) {
        try {
            cacheFile(context).writeText(json.encodeToString(value))
        } catch (e: Exception) {
            Log.e(TAG, "Could not write cache", e)
        }
    }

    fun cachedWallpapers(context: Context): List<CachedWallpaper> {
        val cache = read(context) ?: return emptyList()
        if (cache.configFingerprint != PreferencesManager.configFingerprint()) return emptyList()
        return cache.wallpapers
    }

    fun isStale(context: Context): Boolean {
        val cache = read(context) ?: return true
        if (cache.configFingerprint != PreferencesManager.configFingerprint()) return true
        if (cache.wallpapers.isEmpty()) return true
        return System.currentTimeMillis() - cache.fetchedAtMillis > STALE_AFTER_MILLIS
    }

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
        val width = if (PreferencesManager.fourK) 3840 else 1920
        val height = if (PreferencesManager.fourK) 2160 else 1080

        // /Items accepts a single parentId, so several libraries mean several queries.
        val items = if (config.parentIds.size > 1) {
            val perLibrary = (ITEM_LIMIT / config.parentIds.size).coerceAtLeast(5)
            config.parentIds.flatMap { parentId ->
                client.randomItems(config.copy(parentIds = setOf(parentId)), perLibrary)
            }.shuffled()
        } else {
            client.randomItems(config, ITEM_LIMIT)
        }

        val wallpapers = items.mapNotNull { item ->
            val uri = client.backdropUrl(item, width, height) ?: return@mapNotNull null
            CachedWallpaper(
                uri = uri,
                title = item.seriesName?.takeIf { item.type == "Episode" } ?: item.name,
                subtitle = buildSubtitle(item),
                itemId = item.id,
            )
        }.distinctBy { it.uri }

        write(
            context,
            WallpaperCacheFile(
                fetchedAtMillis = System.currentTimeMillis(),
                configFingerprint = PreferencesManager.configFingerprint(),
                wallpapers = wallpapers,
            )
        )
        Log.i(TAG, "Cached ${wallpapers.size} wallpapers")
        return wallpapers.size
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
