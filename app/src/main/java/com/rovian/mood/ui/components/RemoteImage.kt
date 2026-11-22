package com.rovian.mood.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Small, dependency-free image loader with an in-memory cache, for posters. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String, maxSide: Int = 1920): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 8_000
                readTimeout = 12_000
                try {
                    if (responseCode !in 200..299) null else inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

@Composable
fun RemoteImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<ImageBitmap?>(url?.let { ImageLoader.cached(it)?.asImageBitmap() }, url) {
        if (url != null && value == null) value = ImageLoader.load(url)?.asImageBitmap()
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(450), label = "fade")
    Box(modifier.background(MoodColors.Charcoal)) {
        bitmap?.let {
            Image(it, contentDescription, Modifier.matchParentSize().graphicsLayer { this.alpha = alpha }, contentScale = contentScale)
        }
    }
}

@Composable
fun BitmapImage(bitmap: Bitmap?, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MoodColors.Night)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}
