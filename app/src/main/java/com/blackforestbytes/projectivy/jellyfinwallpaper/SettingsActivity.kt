package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferencesManager.init(this)

        if (!DeepLinks.isInstalled(this, WallpaperRepository.PROJECTIVY_PACKAGE)) {
            Toast.makeText(this, R.string.projectivy_not_installed, Toast.LENGTH_LONG).show()
        }

        val seededAndClosing = applySeedExtras(intent)
        if (seededAndClosing) {
            finish()
            return
        }

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }

    /**
     * Lets the whole plugin be provisioned without a remote:
     *
     *   adb shell am start -n <pkg>/.SettingsActivity \
     *     --es server_url http://10.8.0.14:8096 --es token <key> --es user_id <guid> \
     *     --es wallpaper_style composed --es sort_mode random --ei wallpaper_limit 50 --ez close true
     */
    private fun applySeedExtras(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        var changed = false
        extras.getString("server_url")?.let { PreferencesManager.serverUrl = it; changed = true }
        extras.getString("token")?.let { PreferencesManager.token = it; changed = true }
        extras.getString("user_id")?.let { PreferencesManager.userId = it; changed = true }
        extras.getString("wallpaper_style")?.let {
            PreferencesManager.wallpaperStyle = WallpaperStyle.from(it); changed = true
        }
        extras.getString("sort_mode")?.let {
            PreferencesManager.sortMode = SortMode.from(it); changed = true
        }
        if (extras.containsKey("wallpaper_limit")) {
            PreferencesManager.wallpaperLimit = extras.getInt("wallpaper_limit")
            changed = true
        }
        if (extras.containsKey("balance_types")) {
            PreferencesManager.balanceTypes = extras.getBoolean("balance_types")
            changed = true
        }
        if (changed) WallpaperRepository.refreshAsync(this)
        return changed && extras.getBoolean("close", false)
    }

    override fun onStop() {
        super.onStop()
        // Projectivy already re-requests when settings were opened from its own menu, but this
        // covers the plugin being configured standalone from the launcher's app list.
        WallpaperRepository.refreshAsync(this)
    }
}
