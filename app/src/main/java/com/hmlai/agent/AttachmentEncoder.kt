package com.hmlai.agent

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Converts Android content URIs into bounded, transport-safe payloads.
 * Images are decoded/resized before upload so vision requests don't explode
 * the phone's memory or the HTTP request size. Other small files are passed
 * through as-is so the backend can decide whether it supports that MIME type.
 */
object AttachmentEncoder {
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val MAX_IMAGE_SIDE = 1600

    data class Payload(
        val name: String,
        val mimeType: String,
        val dataBase64: String,
        val originalMimeType: String? = null
    )

    fun encode(resolver: ContentResolver, uri: Uri): Payload? {
        val name = queryName(resolver, uri)
        val originalMime = resolver.getType(uri) ?: guessMime(name)
        val mime = originalMime.lowercase()

        return try {
            if (mime.startsWith("image/")) {
                encodeImage(resolver, uri, name, originalMime)
            } else {
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    if (input.available() > MAX_FILE_BYTES) return null
                    input.readBytes()
                } ?: return null
                if (bytes.size > MAX_FILE_BYTES) return null
                Payload(name, originalMime, Base64.encodeToString(bytes, Base64.NO_WRAP), originalMime)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeImage(resolver: ContentResolver, uri: Uri, name: String, originalMime: String): Payload? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_SIDE || bounds.outHeight / sample > MAX_IMAGE_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        if (bitmap !== scaled) bitmap.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_FILE_BYTES) return null
        return Payload(name, "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), originalMime)
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        var name = uri.lastPathSegment ?: "attachment"
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.let { name = it }
        }
        return name
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
