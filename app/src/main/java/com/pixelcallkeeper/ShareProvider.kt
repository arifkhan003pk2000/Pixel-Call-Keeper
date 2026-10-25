package com.pixelcallkeeper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class ShareProvider : ContentProvider() {

    companion object {
        const val AUTH = "com.pixelcallkeeper.share"
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val base = RecorderService.dir(ctx).canonicalFile
        val rel = uri.path?.removePrefix("/") ?: return null
        val f = File(base, rel).canonicalFile
        return if (f.path.startsWith(base.path + File.separator) && f.isFile) f else null
    }

    private fun displayName(f: File): String {
        val ctx = context ?: return f.name
        val base = RecorderService.dir(ctx).canonicalFile
        return f.relativeTo(base).path.replace(File.separatorChar, '_').replace(' ', '-')
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> displayName(f)
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        c.addRow(row)
        return c
    }

    override fun getType(uri: Uri): String = "audio/wav"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}
