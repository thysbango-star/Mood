package com.rovian.mood.cast.dlna

import com.rovian.mood.cast.MediaKind
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class DlnaProtocolTest {

    @Test
    fun parsesSearchResponse() {
        val r = Ssdp.parse(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:9197/dmr\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: uuid:abc-123::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )
        assertNotNull(r)
        assertEquals("http://192.168.1.20:9197/dmr", r!!.location)
        assertEquals("uuid:abc-123", r.udn)
        assertEquals(1800, r.maxAgeSeconds)
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\nST: x\r\n\r\n"))
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nLOCATION: http://x/\r\nUSN: uuid:a\r\n\r\n"))
    }

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
            <friendlyName>Hub</friendlyName>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Samsung Living Room</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>UE55AU7100</modelName>
                <UDN>uuid:tv-1</UDN>
                <serviceList>
                  <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc/control</controlURL></service>
                  <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>cm/control</controlURL></service>
                  <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av/control</controlURL></service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun parsesEmbeddedRendererAndResolvesControlUrls() {
        val r = DlnaDescription.parse(description, "http://192.168.1.20:9197/dmr/desc.xml")!!
        assertEquals("[TV] Samsung Living Room", r.friendlyName)
        assertEquals("uuid:tv-1", r.udn)
        assertEquals("Samsung Electronics", r.manufacturer)
        assertEquals("http://192.168.1.20:9197/av/control", r.avTransport.controlUrl)
        assertEquals("http://192.168.1.20:9197/rc/control", r.renderingControl!!.controlUrl)
        assertEquals("http://192.168.1.20:9197/dmr/cm/control", r.connectionManager!!.controlUrl)
    }

    @Test
    fun nonRendererIsIgnored() {
        val xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>NAS</friendlyName><serviceList>" +
            "<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cd</controlURL></service>" +
            "</serviceList></device></root>"
        assertNull(DlnaDescription.parse(xml, "http://10.0.0.2/"))
    }

    @Test
    fun timeRoundTrips() {
        assertEquals(3_723_000L, DlnaTime.parse("1:02:03"))
        assertEquals(5_500L, DlnaTime.parse("0:00:05.500"))
        assertNull(DlnaTime.parse("NOT_IMPLEMENTED"))
        assertNull(DlnaTime.parse(""))
        assertEquals("1:02:03", DlnaTime.format(3_723_999))
    }

    @Test
    fun didlIsEscapedXml() {
        val didl = Didl.item("Tom & Jerry <HD>", "http://h/m/t/1/a.mp4?x=1&y=2", "video/mp4", MediaKind.VIDEO, 61_000)
        assertTrue(didl.contains("Tom &amp; Jerry &lt;HD&gt;"))
        assertTrue(didl.contains("x=1&amp;y=2"))
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("duration=\"0:01:01.000\""))
        Xml.parse(didl) // well-formed
    }

    /** End-to-end SOAP against a fake renderer: request shape, response parsing and faults. */
    @Test
    fun soapRoundTripAgainstFakeRenderer() {
        val bodies = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/av") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").orEmpty()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += action to body
            val (code, reply) = when {
                action.endsWith("#GetPositionInfo\"") -> 200 to response(
                    "GetPositionInfo",
                    "<Track>1</Track><TrackDuration>0:03:00</TrackDuration><RelTime>0:01:15</RelTime><TrackURI>http://h/a.mp4</TrackURI>",
                )
                action.endsWith("#Seek\"") -> 500 to fault(710, "Seek mode not supported")
                else -> 200 to response(action.substringAfter('#').trimEnd('"'), "")
            }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val base = "http://127.0.0.1:${http.address.port}"
            val renderer = DlnaRenderer(
                "uuid:x", "TV", null, null, "$base/desc",
                DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "$base/av"), null, null,
            )
            val control = DlnaControl(renderer, SoapClient())
            control.setUri("http://h/a.mp4", Didl.item("A & B", "http://h/a.mp4", "video/mp4", MediaKind.VIDEO, null))
            control.play()
            val pos = control.position()
            assertEquals(75_000L, pos.positionMs)
            assertEquals(180_000L, pos.durationMs)
            assertEquals("http://h/a.mp4", pos.trackUri)
            try {
                control.seek(10_000)
                fail("Seek should have faulted")
            } catch (e: UpnpException) {
                assertEquals(710, e.code)
                assertEquals("the TV can't seek in this media", e.message)
            }
            val (setAction, setBody) = bodies[0]
            assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", setAction)
            assertTrue(setBody.contains("<CurrentURI>http://h/a.mp4</CurrentURI>"))
            // DIDL is XML-escaped once more inside the SOAP body.
            assertTrue(setBody.contains("&lt;dc:title&gt;A &amp;amp; B&lt;/dc:title&gt;"))
            assertTrue(bodies[1].second.contains("<Speed>1</Speed>"))
        } finally {
            http.stop(0)
        }
    }

    private fun response(action: String, inner: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$inner</u:${action}Response>" +
            "</s:Body></s:Envelope>"

    private fun fault(code: Int, description: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
}
