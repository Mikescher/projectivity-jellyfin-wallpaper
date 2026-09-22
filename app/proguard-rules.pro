-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class com.blackforestbytes.projectivy.jellyfinwallpaper.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Binder IPC resolves by descriptor string, but keeping the generated interface is free insurance.
-keep interface tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService { *; }
