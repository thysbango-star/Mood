package com.rovian.mood.cast

/** MIME handling shared by every pathway. */
object MediaFormats {
    fun normalize(mime: String): String {
        val m = mime.substringBefore(';').trim().lowercase()
        return when (m) {
            "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> "audio/mpeg"
            "audio/x-m4a", "audio/m4a", "audio/aacp" -> "audio/mp4"
            "audio/x-aac" -> "audio/aac"
            "audio/x-flac" -> "audio/flac"
            "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav"
            "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> "application/x-mpegurl"
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "video/x-m4v" -> "video/mp4"
            "video/mkv" -> "video/x-matroska"
            else -> m
        }
    }

    // Google Cast supported media: https://developers.google.com/cast/docs/media
    val castVideo = setOf("video/mp4", "video/webm", "video/mp2t", "application/x-mpegurl", "application/dash+xml")
    val castAudio = setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/opus", "audio/wav", "audio/webm")
    val castImage = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
    /** Containers Cast doesn't officially support but some receivers play. */
    val castMaybeVideo = setOf("video/x-matroska", "video/quicktime")

    fun kindOf(mime: String): MediaKind? {
        val m = normalize(mime)
        return when {
            m.startsWith("video/") -> MediaKind.VIDEO
            m.startsWith("audio/") -> MediaKind.AUDIO
            m.startsWith("image/") -> MediaKind.IMAGE
            m == "application/x-mpegurl" || m == "application/dash+xml" -> MediaKind.VIDEO
            else -> null
        }
    }

    private val byExtension = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mov" to "video/quicktime", "ts" to "video/mp2t", "m3u8" to "application/x-mpegurl", "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "opus" to "audio/opus", "wav" to "audio/wav",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
    )

    fun mimeFromName(name: String): String? =
        byExtension[name.substringAfterLast('.', "").substringBefore('?').lowercase()]

    fun extensionFor(mime: String): String =
        byExtension.entries.firstOrNull { it.value == normalize(mime) }?.key ?: "bin"

    fun describe(mime: String): String = when (val m = normalize(mime)) {
        "video/x-matroska" -> "MKV video"
        "video/quicktime" -> "MOV video"
        "application/x-mpegurl" -> "HLS stream"
        "application/dash+xml" -> "DASH stream"
        else -> m.substringAfter('/').uppercase() + " " + (kindOf(m)?.label?.lowercase() ?: "file")
    }
}

sealed interface Compatibility {
    data object Supported : Compatibility
    /** Mood will convert the media on the phone first (e.g. HEIC photo → JPEG). */
    data class Convert(val toMime: String) : Compatibility
    /** The receiver didn't say; Mood will try and report the real result. */
    data class Unverified(val note: String) : Compatibility
    data class Unsupported(val reason: String) : Compatibility
}

object CompatibilityChecker {

    fun check(endpoint: ReceiverEndpoint, kind: MediaKind, mime: String): Compatibility {
        val m = MediaFormats.normalize(mime)
        val caps = endpoint.capabilities
        if (!caps.supports(kind)) {
            return Compatibility.Unsupported(
                when (kind) {
                    MediaKind.VIDEO -> "${endpoint.name} is audio-only, so it can't show video."
                    MediaKind.IMAGE -> "${endpoint.name} can't show photos."
                    MediaKind.AUDIO -> "${endpoint.name} can't play audio."
                },
            )
        }
        return when (endpoint.protocol) {
            CastProtocol.GOOGLE_CAST -> checkCast(endpoint, kind, m)
            CastProtocol.DLNA -> checkDlna(endpoint, kind, m)
        }
    }

    private fun checkCast(endpoint: ReceiverEndpoint, kind: MediaKind, m: String): Compatibility {
        val supported = when (kind) {
            MediaKind.VIDEO -> MediaFormats.castVideo
            MediaKind.AUDIO -> MediaFormats.castAudio
            MediaKind.IMAGE -> MediaFormats.castImage
        }
        return when {
            m in supported -> Compatibility.Supported
            kind == MediaKind.IMAGE -> Compatibility.Convert("image/jpeg")
            m in MediaFormats.castMaybeVideo -> Compatibility.Unverified(
                "Google Cast doesn't officially support ${MediaFormats.describe(m)}. ${endpoint.name} may still play it.",
            )
            else -> Compatibility.Unsupported("Google Cast can't play ${MediaFormats.describe(m)} files.")
        }
    }

    private fun checkDlna(endpoint: ReceiverEndpoint, kind: MediaKind, m: String): Compatibility {
        val declared = endpoint.capabilities.declaredMimeTypes
            ?: return Compatibility.Unverified("${endpoint.name} didn't list the formats it plays, so Mood will try.")
        return when {
            declaredSupports(declared, m) -> Compatibility.Supported
            kind == MediaKind.IMAGE && declaredSupports(declared, "image/jpeg") -> Compatibility.Convert("image/jpeg")
            else -> Compatibility.Unsupported("${endpoint.name} doesn't list ${MediaFormats.describe(m)} among the formats it plays.")
        }
    }

    fun declaredSupports(declared: Set<String>, mime: String): Boolean {
        val m = MediaFormats.normalize(mime)
        return m in declared || "*" in declared || "*/*" in declared || "${m.substringBefore('/')}/*" in declared
    }

    /** Parses a UPnP ConnectionManager `Sink` value into normalised MIME types. */
    fun parseProtocolInfo(sink: String): Set<String> =
        sink.split(',')
            .mapNotNull { entry ->
                val parts = entry.trim().split(':')
                if (parts.size >= 3 && parts[0].trim().equals("http-get", ignoreCase = true)) {
                    MediaFormats.normalize(parts[2])
                } else {
                    null
                }
            }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Whether a set of declared formats includes any of [kind]. */
    fun declaresKind(declared: Set<String>, kind: MediaKind): Boolean =
        declared.any { it == "*" || it == "*/*" || MediaFormats.kindOf(it) == kind }
}

/** Groups endpoints from different protocols that are the same physical screen. */
object DeviceGrouping {
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun group(endpoints: List<ReceiverEndpoint>): List<MoodDevice> {
        val devices = mutableListOf<MoodDevice>()
        endpoints.groupBy { normalize(it.name).ifEmpty { it.key } }.forEach { (nameKey, group) ->
            val oneEachProtocol = group.groupBy { it.protocol }.values.all { it.size == 1 }
            if (oneEachProtocol) {
                val sorted = group.sortedBy { it.protocol.rank }
                devices += MoodDevice(key = nameKey, name = sorted.first().name, endpoints = sorted)
            } else {
                // Two screens share a name (e.g. two "Living Room TV"s) — never merge those.
                group.forEach { devices += MoodDevice(key = it.key, name = it.name, endpoints = listOf(it)) }
            }
        }
        return devices.sortedWith(compareBy({ it.name.lowercase() }, { it.key }))
    }
}
