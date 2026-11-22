package com.rovian.mood.cast.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class LocalMediaServerTest {
    private val server = LocalMediaServer()
    private val bytes = ByteArray(10_000) { (it % 251).toByte() }
    private lateinit var url: String

    @Before
    fun setUp() {
        assertTrue(server.start(InetAddress.getLoopbackAddress()))
        url = server.share(ByteArrayContent(bytes, "video/mp4"), "My Clip.mp4")!!
    }

    @After
    fun tearDown() = server.stop()

    private fun open(u: String = url, method: String = "GET", range: String? = null): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    @Test
    fun servesWholeFile() {
        val c = open()
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertNotNull(c.getHeaderField("contentFeatures.dlna.org"))
        assertArrayEquals(bytes, c.inputStream.readBytes())
    }

    @Test
    fun servesByteRanges() {
        val c = open(range = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/10000", c.getHeaderField("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(100, 200), c.inputStream.readBytes())

        val open = open(range = "bytes=9990-")
        assertEquals(206, open.responseCode)
        assertArrayEquals(bytes.copyOfRange(9990, 10_000), open.inputStream.readBytes())

        val suffix = open(range = "bytes=-5")
        assertArrayEquals(bytes.copyOfRange(9995, 10_000), suffix.inputStream.readBytes())
    }

    @Test
    fun rejectsUnsatisfiableRange() {
        assertEquals(416, open(range = "bytes=20000-").responseCode)
    }

    @Test
    fun headHasNoBody() {
        val c = open(method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals("10000", c.getHeaderField("Content-Length"))
    }

    @Test
    fun unknownTokenOrItemIs404() {
        val parts = url.split('/')
        val wrongToken = url.replace(parts[4], "A".repeat(parts[4].length))
        assertEquals(404, open(wrongToken).responseCode)
        assertEquals(404, open(url.replace("/${parts[5]}/", "/zzz/")).responseCode)
    }

    @Test
    fun revokeInvalidatesEveryUrl() {
        server.revokeAll()
        assertEquals(404, open().responseCode)
    }

    @Test
    fun rangeParsing() {
        assertEquals(ByteRange.Full, ByteRange.parse(null, 10))
        assertEquals(ByteRange.Partial(0, 9), ByteRange.parse("bytes=0-", 10))
        assertEquals(ByteRange.Partial(2, 9), ByteRange.parse("bytes=2-100", 10))
        assertEquals(ByteRange.Partial(7, 9), ByteRange.parse("bytes=-3", 10))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=10-", 10))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1,4-5", 10))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1", null))
    }
}
