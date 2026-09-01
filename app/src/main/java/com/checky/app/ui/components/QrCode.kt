package com.checky.app.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File

fun qrBitmap(payload: String, size: Int = 720): Bitmap {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}

/**
 * Writes the QR payload to the device gallery as a PNG.
 *
 * Exists for the single-device case: the connect window sets FLAG_SECURE so the
 * screen cannot be captured, which leaves someone holding only one phone with no
 * way to scan the code. Saving it lets them open the official app and pick the
 * image from the album instead. The payload is a login ticket that the server
 * expires after about three minutes, so the caller reminds the user to delete
 * the file once the login is confirmed.
 */
fun saveQrToGallery(context: Context, payload: String): Boolean {
    val bitmap = qrBitmap(payload)
    val fileName = "checky_qr_${System.currentTimeMillis()}.png"
    val canUseMediaStore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    return if (canUseMediaStore) {
        insertIntoGallery(context, bitmap, fileName)
    } else {
        saveToAppPictures(context, bitmap, fileName)
    }
}

private fun insertIntoGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Checky")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val stream = resolver.openOutputStream(uri) ?: return false
        stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Fallback for devices that predate the scoped MediaStore inserts. */
private fun saveToAppPictures(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Checky")
    if (!directory.exists() && !directory.mkdirs()) return false
    val file = File(directory, fileName)
    return try {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        true
    } catch (_: Exception) {
        false
    }
}
