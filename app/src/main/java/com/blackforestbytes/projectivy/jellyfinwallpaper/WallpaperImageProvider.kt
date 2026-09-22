package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Serves the images produced by [WallpaperComposer].
 *
 * Projectivy fetches wallpapers in its own process, so composed images have to travel as a
 * `content://` URI rather than a file path. Rendering happens on first open and is then cached on
 * disk — Projectivy re-opens the same wallpaper every time it cycles back to it.
 *
 * The provider must stay exported: Projectivy receives these URIs over AIDL, not in an Intent, so
 * there is no grant to hand it. It only ever serves item ids that are in our own metadata store.
 */
class WallpaperImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val itemId = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        PreferencesManager.init(context)

        val width = if (PreferencesManager.fourK) 3840 else 1920
        val height = if (PreferencesManager.fourK) 2160 else 1080
        val target = File(cacheDir(context), "${itemId}_${width}x$height.jpg")

        synchronized(lock) {
            if (!target.exists()) {
                val meta = WallpaperRepository.composeMeta(context, itemId) ?: run {
                    Log.w(TAG, "No metadata for $itemId")
                    return null
                }
                if (!render(meta, target, width, height)) return null
                trim(cacheDir(context))
            }
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun render(meta: ComposeMeta, target: File, width: Int, height: Int): Boolean {
        var art: Bitmap? = null
        var logo: Bitmap? = null
        var composed: Bitmap? = null
        // Written under a temp name so a reader can never pick up a half-encoded frame.
        val temp = File(target.parentFile, "${target.name}.tmp")
        return try {
            art = ImageFetcher.bytes(meta.backdropUrl)
                ?.let { WallpaperComposer.decode(it, WallpaperComposer.artSize(width)) }
            if (art == null) {
                Log.w(TAG, "No backdrop for ${meta.itemId}")
                return false
            }
            logo = meta.logoUrl
                ?.let { ImageFetcher.bytes(it) }
                ?.let { WallpaperComposer.decode(it, WallpaperComposer.logoRequestWidth(width)) }

            composed = WallpaperComposer.compose(meta, art, logo, width, height)
            temp.outputStream().use { composed.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "Could not compose ${meta.itemId}", e)
            temp.delete()
            false
        } finally {
            art?.recycle()
            logo?.recycle()
            composed?.recycle()
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        files.take((files.size - MAX_CACHED).coerceAtLeast(0)).forEach { it.delete() }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        private const val TAG = "WallpaperImageProvider"
        private const val MIME_TYPE = "image/jpeg"
        private const val MAX_CACHED = 60
        private const val PATH = "image"

        /** Serialises rendering: two 4K canvases at once is 66 MB, and duplicate work besides. */
        private val lock = Any()

        private fun cacheDir(context: Context) =
            File(context.cacheDir, "composed").apply { mkdirs() }

        fun uriFor(context: Context, itemId: String): String =
            "content://${context.packageName}.provider/$PATH/$itemId"

        fun clear(context: Context) {
            synchronized(lock) { cacheDir(context).listFiles()?.forEach { it.delete() } }
        }
    }
}
