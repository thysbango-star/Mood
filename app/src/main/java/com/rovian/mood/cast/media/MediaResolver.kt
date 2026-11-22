package com.rovian.mood.cast.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import com.rovian.mood.cast.CastException
import com.rovian.mood.cast.CastMedia
import com.rovian.mood.cast.MediaFormats
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Streams a `content://` item from the phone. */
class UriContent(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val mimeType: String,
    override val length: Long?,
) : ServableContent {
    override fun open(offset: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        val stream = afd.createInputStream()
        var toSkip = offset
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return stream
    }
}

/** Turns user-picked files and links into [CastMedia], and produces what receivers fetch. */
class MediaResolver(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    suspend fun fromUri(uri: Uri, hint: MediaKind?): CastMedia = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1).takeIf { it > 0 }
                }
            }
        }
        val mime = resolver.getType(uri)?.let(MediaFormats::normalize)
            ?: name?.let(MediaFormats::mimeFromName)
            ?: throw CastException("Mood couldn't tell what kind of file ${name ?: "this"} is.")
        val kind = MediaFormats.kindOf(mime) ?: hint
            ?: throw CastException("${name ?: "That file"} isn't a video, photo or music file.")
        var title = name?.substringBeforeLast('.')?.ifBlank { null } ?: kind.label
        var subtitle: String? = null
        var duration: Long? = null
        if (kind != MediaKind.IMAGE) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(app, uri)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (kind == MediaKind.AUDIO) {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                    subtitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.d(TAG, "No metadata for $uri: ${e.message}")
            } finally {
                runCatching { mmr.release() }
            }
        }
        CastMedia(kind, title, mime, MediaSource.Local(uri.toString(), size), duration, subtitle)
    }

    fun content(media: CastMedia, mimeOverride: String? = null): ServableContent {
        val local = media.source as? MediaSource.Local ?: error("Not a local item")
        return UriContent(resolver, Uri.parse(local.contentUri), mimeOverride ?: media.mimeType, local.sizeBytes)
    }

    /** Re-encodes a local photo as JPEG (≤ 3840 px) for receivers that can't show its format. */
    suspend fun transcodeToJpeg(media: CastMedia): ByteArrayContent = withContext(Dispatchers.IO) {
        val local = media.source as? MediaSource.Local ?: throw CastException("Only photos on this phone can be converted.")
        val bitmap = decodeBounded(Uri.parse(local.contentUri), 3840)
            ?: throw CastException("Mood couldn't read ${media.title} to convert it.")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        ByteArrayContent(out.toByteArray(), "image/jpeg")
    }

    /** Album art embedded in a local audio file, for the TV's now-playing screen. */
    suspend fun embeddedArtwork(media: CastMedia): ByteArray? = withContext(Dispatchers.IO) {
        val local = media.source as? MediaSource.Local ?: return@withContext null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(app, Uri.parse(local.contentUri))
            mmr.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Artwork for the phone's controller screen. */
    suspend fun artwork(media: CastMedia): Bitmap? = withContext(Dispatchers.IO) {
        val local = media.source as? MediaSource.Local ?: return@withContext null
        val uri = Uri.parse(local.contentUri)
        try {
            when (media.kind) {
                MediaKind.IMAGE -> decodeBounded(uri, 1600)
                MediaKind.VIDEO ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        resolver.loadThumbnail(uri, Size(1280, 720), null)
                    } else {
                        frameAt(uri)
                    }
                MediaKind.AUDIO -> embeddedArtwork(media)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } catch (e: Exception) {
            if (media.kind == MediaKind.VIDEO) runCatching { frameAt(uri) }.getOrNull() else null
        }
    }

    private fun frameAt(uri: Uri): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(app, uri)
            mmr.getFrameAtTime(1_000_000)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeBounded(uri: Uri, maxSide: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }

    /**
     * Validates a link and checks — from the phone — that it is reachable media.
     * The TV fetches it itself, so this can't promise the TV will reach it, but it
     * catches dead, expired and non-media links before anything is sent.
     */
    suspend fun fromUrl(raw: String): CastMedia = withContext(Dispatchers.IO) {
        val text = raw.trim()
        val uri = runCatching { URI(text) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw CastException("That isn't a valid web link. It should start with http:// or https://")
        }
        val url = uri.toURL()
        val (code, contentType) = probe(url, "HEAD").let { if (it.first == 405 || it.first == 501) probe(url, "GET") else it }
        when {
            code == 401 || code == 403 -> throw CastException("That link needs a sign-in or has expired (the server said $code).")
            code == 404 || code == 410 -> throw CastException("Nothing was found at that link — it may have expired.")
            code >= 400 -> throw CastException("The server rejected that link (error $code).")
        }
        val mime = contentType?.let(MediaFormats::normalize)?.takeIf { MediaFormats.kindOf(it) != null }
            ?: MediaFormats.mimeFromName(uri.path.orEmpty())
            ?: if (contentType?.startsWith("text/html") == true) {
                throw CastException("That's a web page, not a media file. Use a direct link to a video, audio file or photo.")
            } else {
                throw CastException("Mood couldn't tell what kind of media that link is.")
            }
        val kind = MediaFormats.kindOf(mime) ?: throw CastException("That link isn't video, audio or a photo.")
        val title = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.')
            .let { java.net.URLDecoder.decode(it, "UTF-8") }.ifBlank { uri.host }
        CastMedia(kind, title, mime, MediaSource.Remote(url.toString()))
    }

    /** True if [url] answers with media from this phone right now (2xx/206). */
    suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val u = URL(url)
            val (code, _) = probe(u, "HEAD").let { if (it.first == 405 || it.first == 501) probe(u, "GET") else it }
            code in 200..299
        }.getOrDefault(false)
    }

    private fun probe(url: URL, method: String): Pair<Int, String?> {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            if (method == "GET") conn.setRequestProperty("Range", "bytes=0-0")
            conn.responseCode to conn.contentType
        } catch (e: IOException) {
            throw CastException("Couldn't reach that link. Check the address and your internet connection.", e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "MoodMedia"
    }
}
