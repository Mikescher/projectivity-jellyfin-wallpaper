package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

@Suppress("WrongConstant") // IntDef annotations on the vendored api module confuse lint here
class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {

        override fun getWallpapers(event: Event?): List<Wallpaper> {
            if (event !is Event.TimeElapsed && event !is Event.LauncherIdleModeChanged) {
                return emptyList()
            }

            // Never fetch here — this call is on a binder thread with a short timeout.
            val cached = WallpaperRepository.cachedWallpapers(this@WallpaperProviderService)
            if (WallpaperRepository.isStale(this@WallpaperProviderService)) {
                WallpaperRepository.refreshAsync(this@WallpaperProviderService)
            }

            val composed = PreferencesManager.wallpaperStyle == WallpaperStyle.COMPOSED
            val player = DeepLinks.resolve(this@WallpaperProviderService, PreferencesManager.clientPackage)

            return cached.map { wallpaper ->
                Wallpaper(
                    uri = wallpaper.uri,
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    // A composed image already carries its title and metadata as pixels, so
                    // Projectivy's own overlay would only duplicate them.
                    title = wallpaper.title.takeUnless { composed },
                    source = wallpaper.subtitle.takeUnless { composed },
                    author = null,
                    actionUri = player?.actionUri(wallpaper.itemId),
                )
            }
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }
}

/**
 * Builds the intent URI Projectivy fires when a wallpaper is selected.
 *
 * Projectivy parses these with Intent.parseUri, so anything in [Player.actionUri] that is not a
 * scheme URI must be in URI_INTENT_SCHEME form.
 */
object DeepLinks {

    const val AUTO = "auto"
    const val NONE = ""

    const val JELLYFIN = "org.jellyfin.androidtv"
    const val JELLYFIN_DEBUG = "org.jellyfin.androidtv.debug"
    const val MOONFIN = "org.moonfin.androidtv"
    const val WHOLPHIN = "com.github.damontecres.wholphin"
    const val FINDROID = "org.findroid"
    const val FLADDER = "tv.fladder"
    const val JELLYFIN_MOBILE = "org.jellyfin.mobile"

    enum class Link {
        /** Jellyfin for Android TV takes the item on its startup activity. */
        JELLYFIN_TV,

        /** Registers a scheme of its own. */
        MOONFIN_SCHEME,

        /** The `jellyfin://items/<id>` scheme the mobile-style clients handle. */
        JELLYFIN_SCHEME,

        /** No published item deep link — just bring the app up. */
        LAUNCH,
    }

    data class Player(val packageName: String, val label: String, val link: Link) {
        fun actionUri(itemId: String): String = when (link) {
            Link.JELLYFIN_TV ->
                "intent:#Intent;component=$packageName/org.jellyfin.androidtv.ui.startup.StartupActivity;" +
                    "action=android.intent.action.VIEW;S.ItemId=$itemId;S.id=$itemId;end"
            Link.MOONFIN_SCHEME -> "moonfin://item?id=$itemId"
            Link.JELLYFIN_SCHEME ->
                "intent://items/$itemId#Intent;scheme=jellyfin;package=$packageName;" +
                    "action=android.intent.action.VIEW;end"
            Link.LAUNCH ->
                "intent:#Intent;package=$packageName;action=android.intent.action.MAIN;end"
        }
    }

    /** Probed in this order by [AUTO]; every package here is also declared in <queries>. */
    val PLAYERS = listOf(
        Player(WHOLPHIN, "Wholphin", Link.LAUNCH),
        Player(MOONFIN, "Moonfin", Link.MOONFIN_SCHEME),
        Player(JELLYFIN, "Jellyfin", Link.JELLYFIN_TV),
        Player(JELLYFIN_DEBUG, "Jellyfin (debug)", Link.JELLYFIN_TV),
        Player(FINDROID, "Findroid", Link.JELLYFIN_SCHEME),
        Player(FLADDER, "Fladder", Link.JELLYFIN_SCHEME),
        Player(JELLYFIN_MOBILE, "Jellyfin Mobile", Link.JELLYFIN_SCHEME),
    )

    fun resolve(context: Context, clientPackage: String): Player? = when (clientPackage) {
        NONE -> null
        AUTO -> PLAYERS.firstOrNull { isInstalled(context, it.packageName) }
        else -> PLAYERS.firstOrNull { it.packageName == clientPackage }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
