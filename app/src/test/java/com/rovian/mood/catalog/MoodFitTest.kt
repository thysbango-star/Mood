package com.rovian.mood.catalog

import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.ReceiverCapabilities
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.ReceiverKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodFitTest {
    private val bunny = runBlocking { OpenFilmsCatalog().search("bunny") }.single()

    private fun ep(protocol: CastProtocol, declared: Set<String>? = null, video: Boolean = true) = ReceiverEndpoint(
        protocol, "id", "TV", null, ReceiverKind.TV,
        ReceiverCapabilities(video = video, audio = true, image = video, declaredMimeTypes = declared),
    )

    @Test
    fun castGetsTheAdaptiveStream() {
        val choice = MoodFit.choose(bunny, ep(CastProtocol.GOOGLE_CAST))!!
        assertEquals("application/x-mpegurl", choice.stream.mimeType)
    }

    @Test
    fun dlnaTvWithoutHlsGetsTheFixedRendition() {
        val choice = MoodFit.choose(bunny, ep(CastProtocol.DLNA, declared = setOf("video/mp4", "audio/mpeg")))!!
        assertEquals("video/mp4", choice.stream.mimeType)
    }

    @Test
    fun audioOnlyReceiverGetsNothing() {
        assertNull(MoodFit.choose(bunny, ep(CastProtocol.GOOGLE_CAST, video = false)))
    }

    @Test
    fun everyCatalogueMovieIsCastableToCast() = runBlocking {
        OpenFilmsCatalog().home().rows.flatMap { it.movies }.forEach {
            check(MoodFit.choose(it, ep(CastProtocol.GOOGLE_CAST)) != null) { "${it.title} has no Cast stream" }
        }
    }
}
