# Jellyfin Wallpapers for Projectivy

A wallpaper provider plugin for [Projectivy Launcher](https://projectivylauncher.com/) that pulls
backdrops directly from a Jellyfin server — no middleman backend, no Android TV channel scraping.

It replaces the stock *Random program* provider, which sources images from the Android TV home
screen channel database and therefore picks up YouTube, Prime Video and anything else that publishes
a channel, with no way to filter by genre.

## Requirements

- **Projectivy Premium** — external wallpaper providers are a premium feature
- Jellyfin 12.x (tested against 12.1) or 10.11.x
- An admin API key: Jellyfin Dashboard → Advanced → API Keys

## Filters

- Libraries
- Content types (Movie / Series)
- Genres
- Age ratings
- Unwatched only

Leaving a list empty means "allow everything".

## Setup

Install the APK, then in Projectivy: **Settings → Appearance → Wallpaper → Wallpaper source →
Jellyfin Wallpapers**, and open its settings with the gear button.

Enter the server URL (`http://10.8.0.14:8096`) and API key. The user, library and genre pickers
populate themselves once the connection succeeds.

Settings can also be seeded over ADB:

```sh
adb shell am start -n com.blackforestbytes.projectivy.jellyfinwallpaper/.SettingsActivity \
  --es server_url "http://10.8.0.14:8096" \
  --es token "<api-key>" \
  --ez close true
```

## Build

Requires JDK 17–21; **JDK 26 will not work** with the Android Gradle Plugin.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleDebug
```

## Notes

- Image URLs are handed to Projectivy, which fetches them in its own process. Jellyfin's item image
  endpoints are unauthenticated, so no token travels with them. The flip side is that Projectivy
  only trusts system CAs — a self-signed or private-CA HTTPS server would need images proxied
  through a local `FileProvider` instead.
- `getWallpapers()` is a binder call with a short timeout, so it only ever reads the on-disk cache.
  Refreshes run in the background and signal Projectivy with `ACTION_WALLPAPER_PROVIDER_UPDATED`.
- Projectivy caches its plugin list at startup. If the plugin does not appear, force-stop the
  launcher and reopen it.

`api/` is vendored unchanged from
[spocky/projectivy-plugin-wallpaper-provider](https://github.com/spocky/projectivy-plugin-wallpaper-provider)
(Apache 2.0).
