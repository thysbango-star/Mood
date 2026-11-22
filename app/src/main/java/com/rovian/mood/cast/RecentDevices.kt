package com.rovian.mood.cast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentDevice(
    val protocol: CastProtocol,
    val id: String,
    val name: String,
    val model: String?,
    val lastUsed: Long,
) {
    val endpointKey: String get() = "${protocol.name}:$id"
}

/** Screens the user connected to before, kept on the phone only. */
class RecentDevices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mood_recent_devices", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentDevice>> = _items.asStateFlow()

    fun remember(endpoint: ReceiverEndpoint) {
        if (endpoint.id.startsWith("session:") || endpoint.protocol == CastProtocol.SCREEN) return
        val updated = listOf(
            RecentDevice(endpoint.protocol, endpoint.id, endpoint.name, endpoint.model, System.currentTimeMillis()),
        ) + _items.value.filterNot { it.endpointKey == endpoint.key }
        _items.value = updated.take(MAX)
        save(_items.value)
    }

    fun forget(endpointKey: String) {
        _items.value = _items.value.filterNot { it.endpointKey == endpointKey }
        save(_items.value)
    }

    private fun load(): List<RecentDevice> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val protocol = runCatching { CastProtocol.valueOf(o.getString("protocol")) }.getOrNull() ?: return@mapNotNull null
            RecentDevice(protocol, o.getString("id"), o.getString("name"), o.optString("model").ifBlank { null }, o.getLong("lastUsed"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<RecentDevice>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("protocol", it.protocol.name)
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("model", it.model ?: "")
                    .put("lastUsed", it.lastUsed),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "devices"
        const val MAX = 6
    }
}
