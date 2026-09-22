# Jellyfin Wallpapers for Projectivy

A wallpaper provider plugin for [Projectivy Launcher](https://projectivylauncher.com/) that pulls
backdrops directly from a Jellyfin server — no middleman backend, no Android TV channel scraping.

It replaces the stock *Random program* provider, which sources images from the Android TV home
screen channel database and therefore picks up YouTube, Prime Video and anything else that publishes
a channel, with no way to filter by genre.

It is a superset of [z9m/ProjectIvy-Plugin-Wallpaper-Jellyfin](https://github.com/z9m/ProjectIvy-Plugin-Wallpaper-Jellyfin):
everything that plugin does is reachable here through **Wallpaper style → Composed card**, plus the
filtering, the user/library pickers and the plain-backdrop mode it does not have.

## Requirements

- **Projectivy Premium** — external wallpaper providers are a premium feature
- Jellyfin 12.x (tested against 12.1) or 10.11.x
- An admin API key: Jellyfin Dashboard → Advanced → API Keys

## Wallpaper styles

**Plain backdrop** (default) hands Projectivy the Jellyfin backdrop URL and lets the launcher draw
its own title overlay. Nothing is downloaded or rendered in this process.

**Composed card** renders the backdrop, the title logo, a metadata line (year · runtime · ★ rating ·
age rating · genres) and the plot into one image, and serves it to Projectivy over a `content://`
URI. This is the z9m layout. Projectivy's own title overlay is left empty, because the image already
carries it.

## Filters

- Libraries
- Content types (Movie / Series)
- Mix types evenly
- Genres
- Age ratings
- Watch state (all / unwatched only / watched only)
- Order (random / recently added)
- Item count (25 / 50 / 100 / 250 / 500 / 1000)

Leaving a list empty means "allow everything".

**Mix types evenly** draws the same number of items per content type. Without it a single query
covers all types at once, so the mix follows library population — a few hundred movies next to a few
thousand series show up as almost nothing but series.

**Item count** is what decides how long the launcher goes before repeating itself. Projectivy asks
for the list once and reuses it for the plugin's cache period (30 minutes), so at a 15-second
rotation interval 100 items are exhausted in 25 minutes. Pick a count that covers the period: 250
items last an hour, 500 two hours. The list is reshuffled on every request, and a new random draw is
fetched from Jellyfin whenever the cached one is older than 20 minutes.

## Open with

Pressing OK on a wallpaper opens the item in a Jellyfin client. **Auto-detect** picks the first of
Wholphin, Moonfin, Jellyfin for Android TV, Findroid, Fladder or Jellyfin Mobile that is installed;
any of them can also be pinned explicitly, or the action disabled with *None*.

Wholphin (`wholphin://view`), Moonfin, Fladder (`fladder://details`) and Jellyfin for Android TV
land on the item itself. Findroid publishes no deep link, so it is only brought to the foreground.

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
  --es wallpaper_style composed \
  --es sort_mode random \
  --ei wallpaper_limit 250 \
  --ez balance_types true \
  --ez close true
```

## Build

Requires JDK 17–21; **JDK 26 will not work** with the Android Gradle Plugin.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-temurin ./gradlew :app:assembleDebug
```

## Notes

- In plain-backdrop mode, image URLs are handed to Projectivy, which fetches them in its own
  process. Jellyfin's item image endpoints are unauthenticated, so no token travels with them. The
  flip side is that Projectivy only trusts system CAs — a self-signed or private-CA HTTPS server
  would need images proxied through a local `FileProvider` instead. Composed mode moves the fetch
  into this process, but its HTTP client trusts the same system CAs, so that case is still open.
- Composed images are rendered on first use and then cached on disk, so cycling back to a wallpaper
  costs nothing. The cache is dropped whenever a setting that changes how an item looks changes.
- The content provider has to be exported — Projectivy receives wallpaper URIs over AIDL, not in an
  Intent, so there is no per-URI grant to hand it. It only serves item ids that are in the plugin's
  own metadata store.
- `getWallpapers()` is a binder call with a short timeout, so it only ever reads the on-disk cache.
  Refreshes run in the background and signal Projectivy with `ACTION_WALLPAPER_PROVIDER_UPDATED`.
- Projectivy caches its plugin list at startup. If the plugin does not appear, force-stop the
  launcher and reopen it.

`api/` is vendored unchanged from
[spocky/projectivy-plugin-wallpaper-provider](https://github.com/spocky/projectivy-plugin-wallpaper-provider)
(Apache 2.0).
