package com.rovian.mood.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {
    private fun endpoint(
        protocol: CastProtocol,
        name: String = "TV",
        video: Boolean = true,
        declared: Set<String>? = null,
        id: String = name,
    ) = ReceiverEndpoint(
        protocol, id, name, null, ReceiverKind.TV,
        ReceiverCapabilities(video = video, audio = true, image = video, declaredMimeTypes = declared),
    )

    @Test
    fun parsesSinkProtocolInfo() {
        val sink = "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL,http-get:*:audio/mp3:*,rtsp-rtp-udp:*:video/x:*, http-get:*:image/jpeg:*"
        assertEquals(setOf("video/mp4", "audio/mpeg", "image/jpeg"), CompatibilityChecker.parseProtocolInfo(sink))
    }

    @Test
    fun castChecks() {
        val tv = endpoint(CastProtocol.GOOGLE_CAST)
        assertEquals(Compatibility.Supported, CompatibilityChecker.check(tv, MediaKind.VIDEO, "video/mp4"))
        assertTrue(CompatibilityChecker.check(tv, MediaKind.IMAGE, "image/heic") is Compatibility.Convert)
        assertTrue(CompatibilityChecker.check(tv, MediaKind.VIDEO, "video/x-matroska") is Compatibility.Unverified)
        assertTrue(CompatibilityChecker.check(tv, MediaKind.VIDEO, "video/x-msvideo") is Compatibility.Unsupported)
        val speaker = endpoint(CastProtocol.GOOGLE_CAST, "Kitchen", video = false)
        assertTrue(CompatibilityChecker.check(speaker, MediaKind.VIDEO, "video/mp4") is Compatibility.Unsupported)
        assertEquals(Compatibility.Supported, CompatibilityChecker.check(speaker, MediaKind.AUDIO, "audio/mp3"))
    }

    @Test
    fun dlnaChecksUseDeclaredFormats() {
        val tv = endpoint(CastProtocol.DLNA, declared = setOf("video/mp4", "video/x-matroska", "image/jpeg"))
        assertEquals(Compatibility.Supported, CompatibilityChecker.check(tv, MediaKind.VIDEO, "video/x-matroska"))
        assertTrue(CompatibilityChecker.check(tv, MediaKind.VIDEO, "video/webm") is Compatibility.Unsupported)
        assertTrue(CompatibilityChecker.check(tv, MediaKind.IMAGE, "image/webp") is Compatibility.Convert)
        val silent = endpoint(CastProtocol.DLNA, declared = null)
        assertTrue(CompatibilityChecker.check(silent, MediaKind.VIDEO, "video/webm") is Compatibility.Unverified)
    }

    @Test
    fun groupsSameScreenAcrossProtocols() {
        val cast = endpoint(CastProtocol.GOOGLE_CAST, "Living Room TV")
        val dlna = endpoint(CastProtocol.DLNA, "Living room TV")
        val other = endpoint(CastProtocol.DLNA, "Bedroom")
        val devices = DeviceGrouping.group(listOf(dlna, other, cast))
        assertEquals(2, devices.size)
        val living = devices.first { it.name.startsWith("Living") }
        assertEquals(CastProtocol.GOOGLE_CAST, living.primary.protocol)
        assertEquals(2, living.endpoints.size)
    }

    @Test
    fun neverMergesTwoScreensWithTheSameName() {
        val a = endpoint(CastProtocol.GOOGLE_CAST, "TV", id = "a")
        val b = endpoint(CastProtocol.GOOGLE_CAST, "TV", id = "b")
        assertEquals(2, DeviceGrouping.group(listOf(a, b)).size)
    }
}
