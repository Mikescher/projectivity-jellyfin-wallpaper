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
            val actionUriFor = DeepLinks.actionUriBuilder(
                this@WallpaperProviderService,
                PreferencesManager.clientPackage,
            )

            val wallpapers = ArrayList<Wallpaper>(cached.size)
            var chars = 0
            // Projectivy holds the list it gets for itemsCacheDurationMillis and only re-asks when
            // that expires, so handing it a different order every time is what keeps a long idle
            // stretch from replaying the same sequence.
            for (wallpaper in cached.shuffled()) {
                val actionUri = actionUriFor(wallpaper.itemId)
                // A composed image already carries its title and metadata as pixels, so
                // Projectivy's own overlay would only duplicate them.
                val title = wallpaper.title.takeUnless { composed }
                val source = wallpaper.subtitle.takeUnless { composed }
                chars += wallpaper.uri.length + (title?.length ?: 0) +
                    (source?.length ?: 0) + (actionUri?.length ?: 0)
                if (chars > PARCEL_BUDGET_CHARS) break
                wallpapers += Wallpaper(
                    uri = wallpaper.uri,
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = title,
                    source = source,
                    author = null,
                    actionUri = actionUri,
                )
            }
            return wallpapers
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }

    companion object {
        /**
         * The reply travels over binder, whose transaction buffer is 1 MB for the whole process and
         * shared with every other call in flight. Strings are parcelled as UTF-16, so this cap on
         * the summed string length keeps a large item count well clear of the ceiling.
         */
        private const val PARCEL_BUDGET_CHARS = 200_000
    }
}

/**
 * Builds the intent URI Projectivy fires when a wallpaper is selected.
 *
 * Projectivy parses these with Intent.parseUri, so anything in [actionUriBuilder] that is not a
 * scheme URI must be in URI_INTENT_SCHEME form.
 */
object DeepLinks {

    const val AUTO = "auto"
    const val NONE = ""

    const val JELLYFIN = "org.jellyfin.androidtv"
    const val JELLYFIN_DEBUG = "org.jellyfin.androidtv.debug"
    const val MOONFIN = "org.moonfin.androidtv"
    const val WHOLPHIN = "com.github.damontecres.wholphin"
    const val FINDROID = "dev.jdtech.jellyfin"
    const val FLADDER = "nl.jknaapen.fladder"
    const val JELLYFIN_MOBILE = "org.jellyfin.mobile"

    enum class Link {
        /** Jellyfin for Android TV takes the item on its startup activity. */
        JELLYFIN_TV,

        /** `wholphin://view?itemId=…`; serverId picks the account on a multi-server install. */
        WHOLPHIN_VIEW,

        MOONFIN_SCHEME,

        FLADDER_SCHEME,

        /** The `jellyfin://items/<id>` scheme the mobile-style clients handle. */
        JELLYFIN_SCHEME,

        /** No published item deep link — just bring the app up. */
        LAUNCH,
    }

    data class Player(val packageName: String, val label: String, val link: Link)

    /** Probed in this order by [AUTO]; every package here is also declared in <queries>. */
    val PLAYERS = listOf(
        Player(WHOLPHIN, "Wholphin", Link.WHOLPHIN_VIEW),
        Player(MOONFIN, "Moonfin", Link.MOONFIN_SCHEME),
        Player(JELLYFIN, "Jellyfin", Link.JELLYFIN_TV),
        Player(JELLYFIN_DEBUG, "Jellyfin (debug)", Link.JELLYFIN_TV),
        Player(FINDROID, "Findroid", Link.LAUNCH),
        Player(FLADDER, "Fladder", Link.FLADDER_SCHEME),
        Player(JELLYFIN_MOBILE, "Jellyfin Mobile", Link.JELLYFIN_SCHEME),
    )

    fun resolve(context: Context, clientPackage: String): Player? = when (clientPackage) {
        NONE -> null
        AUTO -> PLAYERS.firstOrNull { isInstalled(context, it.packageName) }
        else -> PLAYERS.firstOrNull { it.packageName == clientPackage }
    }

    /**
     * Resolves the configured player once and returns a per-item URI builder. [Link.LAUNCH] costs a
     * PackageManager query, and a wallpaper list runs to hundreds of items.
     */
    fun actionUriBuilder(context: Context, clientPackage: String): (String) -> String? {
        val player = resolve(context, clientPackage) ?: return { null }
        val serverId = PreferencesManager.serverId
        val launchUri by lazy { resolveLaunchUri(context, player.packageName) }
        return { itemId ->
            when (player.link) {
                Link.JELLYFIN_TV ->
                    "intent:#Intent;component=${player.packageName}/" +
                        "org.jellyfin.androidtv.ui.startup.StartupActivity;" +
                        "action=android.intent.action.VIEW;S.ItemId=$itemId;S.id=$itemId;end"
                Link.WHOLPHIN_VIEW ->
                    "wholphin://view?itemId=$itemId" +
                        if (serverId.isNotEmpty()) "&serverId=$serverId" else ""
                Link.MOONFIN_SCHEME -> "moonfin://item?id=$itemId"
                Link.FLADDER_SCHEME -> "fladder://details?id=$itemId"
                Link.JELLYFIN_SCHEME ->
                    "intent://items/$itemId#Intent;scheme=jellyfin;package=${player.packageName};" +
                        "action=android.intent.action.VIEW;end"
                Link.LAUNCH -> launchUri
            }
        }
    }

    /**
     * A launcher activity declares MAIN + LAUNCHER but not DEFAULT, and startActivity only matches
     * an implicit intent against filters that carry DEFAULT — so `action=MAIN;package=…` resolves
     * to nothing at all. Only the concrete component launches the app.
     */
    private fun resolveLaunchUri(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val component = (pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName))?.component ?: return null
        return "intent:#Intent;component=${component.flattenToShortString()};" +
            "action=android.intent.action.MAIN;end"
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
