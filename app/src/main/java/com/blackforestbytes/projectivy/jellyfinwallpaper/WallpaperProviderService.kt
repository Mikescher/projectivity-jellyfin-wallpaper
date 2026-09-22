package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.app.Service
import android.content.Intent
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
            if (event !is Event.TimeElapsed) return emptyList()

            // Never fetch here — this call is on a binder thread with a short timeout.
            val cached = WallpaperRepository.cachedWallpapers(this@WallpaperProviderService)
            if (WallpaperRepository.isStale(this@WallpaperProviderService)) {
                WallpaperRepository.refreshAsync(this@WallpaperProviderService)
            }

            return cached.map { wallpaper ->
                Wallpaper(
                    uri = wallpaper.uri,
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = wallpaper.title,
                    source = wallpaper.subtitle,
                    author = null,
                    actionUri = DeepLinks.forItem(PreferencesManager.clientPackage, wallpaper.itemId),
                )
            }
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }
}

object DeepLinks {

    const val JELLYFIN = "org.jellyfin.androidtv"
    const val MOONFIN = "org.moonfin.androidtv"
    const val WHOLPHIN = "com.github.damontecres.wholphin"

    /** Projectivy parses this with Intent.parseUri, so it must be URI_INTENT_SCHEME form. */
    fun forItem(clientPackage: String, itemId: String): String? = when (clientPackage) {
        JELLYFIN ->
            "intent:#Intent;component=$JELLYFIN/org.jellyfin.androidtv.ui.startup.StartupActivity;" +
                "action=android.intent.action.VIEW;S.ItemId=$itemId;S.id=$itemId;end"
        // Flutter app with no stable activity to target; it registers a scheme instead.
        MOONFIN -> "moonfin://item?id=$itemId"
        // No published item deep link, so just bring the app up.
        WHOLPHIN -> "intent:#Intent;package=$WHOLPHIN;action=android.intent.action.MAIN;end"
        else -> null
    }
}
