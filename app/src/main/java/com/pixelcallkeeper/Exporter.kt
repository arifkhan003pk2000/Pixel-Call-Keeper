package com.pixelcallkeeper

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Exporter {

    fun mime(f: File): String =
        if (f.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "audio/wav"

    // Download/PixelCallKeeper/<Naam>/<Naam>_<Type>_<waqt>.m4a
    fun toDownloads(ctx: Context, f: File, base: File): Boolean {
        return try {
            val rel = f.relativeTo(base).path.split(File.separatorChar)
            val person = if (rel.size >= 2) rel[0] else "Recordings"
            val type = if (rel.size >= 3) rel[1] else ""
            val name = listOf(person, type, f.name).filter { it.isNotBlank() }
                .joinToString("_").replace(' ', '-')
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(f))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PixelCallKeeper/" + person)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
