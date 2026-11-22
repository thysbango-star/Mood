package com.rovian.mood.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rovian.mood.cast.CastMedia
import com.rovian.mood.cast.CastException
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.MediaSource
import com.rovian.mood.cast.media.MediaResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A video, song or photo the user added to Mood. The file itself stays where it is on the phone. */
data class LibraryItem(
    val id: String,
    val uri: String,
    val title: String,
    val mimeType: String,
    val kind: MediaKind,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val addedAt: Long,
) {
    fun toCastMedia() = CastMedia(
        kind = kind,
        title = title,
        mimeType = mimeType,
        source = MediaSource.Local(uri, sizeBytes),
        durationMs = durationMs,
        id = id,
    )
}

/**
 * "Your Library": the user's own media, kept as long-lived references (Android's
 * persistable read permission) rather than copies, so nothing is duplicated and
 * nothing leaves the phone until the user casts it.
 */
class LibraryStore(context: Context, private val resolver: MediaResolver) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mood_library", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Adds picked files; returns how many were new. */
    suspend fun add(uris: List<Uri>): Int {
        _importing.value = true
        try { return addAll(uris) } finally { _importing.value = false }
    }

    private suspend fun addAll(uris: List<Uri>): Int {
        var added = 0
        val current = _items.value.toMutableList()
        for (uri in uris) {
            if (current.any { it.uri == uri.toString() }) continue
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "No persistable permission for $uri", it) }
            val media = try {
                resolver.fromUri(uri, null)
            } catch (e: CastException) {
                Log.w(TAG, "Skipping $uri: ${e.message}")
                continue
            }
            current.add(
                0,
                LibraryItem(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    title = media.title,
                    mimeType = media.mimeType,
                    kind = media.kind,
                    durationMs = media.durationMs,
                    sizeBytes = (media.source as? MediaSource.Local)?.sizeBytes,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            added++
        }
        _items.value = current
        save(current)
        return added
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _items.value = _items.value.filterNot { it.id == id }
        save(_items.value)
    }

    private fun load(): List<LibraryItem> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val kind = runCatching { MediaKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            LibraryItem(
                id = o.getString("id"),
                uri = o.getString("uri"),
                title = o.getString("title"),
                mimeType = o.getString("mime"),
                kind = kind,
                durationMs = o.optLong("duration", -1).takeIf { it > 0 },
                sizeBytes = o.optLong("size", -1).takeIf { it > 0 },
                addedAt = o.optLong("added"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<LibraryItem>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("uri", it.uri).put("title", it.title).put("mime", it.mimeType)
                    .put("kind", it.kind.name).put("duration", it.durationMs ?: -1).put("size", it.sizeBytes ?: -1)
                    .put("added", it.addedAt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val TAG = "MoodLibrary"
        const val KEY = "items"
    }
}
