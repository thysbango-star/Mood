package com.rovian.mood.cast.dlna

import com.rovian.mood.cast.CompatibilityChecker
import com.rovian.mood.cast.MediaKind
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// ---- SSDP ----------------------------------------------------------------------

data class SsdpResponse(val location: String, val usn: String, val target: String?, val maxAgeSeconds: Int?) {
    /** The device's UDN (`uuid:…`), shared by all of its USNs. */
    val udn: String get() = usn.substringBefore("::")
}

object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun searchMessage(target: String, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: Android/1 UPnP/1.1 Mood/0.2\r\n\r\n"

    /** Parses an M-SEARCH response (or NOTIFY alive). Returns null for anything else. */
    fun parse(datagram: String): SsdpResponse? {
        val lines = datagram.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        val isResponse = first.startsWith("HTTP/1.1 200", ignoreCase = true) || first.startsWith("HTTP/1.0 200", ignoreCase = true)
        val isNotify = first.startsWith("NOTIFY", ignoreCase = true)
        if (!isResponse && !isNotify) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        if (isNotify && headers["nts"]?.contains("byebye", ignoreCase = true) == true) return null
        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        val usn = headers["usn"]?.takeIf { it.isNotBlank() } ?: return null
        val maxAge = headers["cache-control"]?.let {
            Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        return SsdpResponse(location, usn, headers["st"] ?: headers["nt"], maxAge)
    }
}

// ---- Device description ----------------------------------------------------------

data class DlnaService(val type: String, val controlUrl: String)

data class DlnaRenderer(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val location: String,
    val avTransport: DlnaService,
    val renderingControl: DlnaService?,
    val connectionManager: DlnaService?,
)

internal object Xml {
    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim().removePrefix("﻿"))))
    }

    val Node.local: String get() = localName ?: nodeName.substringAfter(':')

    fun Node.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.local == name) out += n
        }
        return out
    }

    fun Node.child(name: String): Element? = children(name).firstOrNull()

    fun Node.childText(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun Document.all(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

object DlnaDescription {
    /** Finds the MediaRenderer (device with an AVTransport service) in a UPnP device description. */
    fun parse(xml: String, location: String): DlnaRenderer? { with(Xml) {
        val doc = parse(xml)
        val base = doc.documentElement.childText("URLBase") ?: location
        for (device in doc.all("device")) {
            val services = device.child("serviceList")?.children("service").orEmpty().mapNotNull { s ->
                val type = s.childText("serviceType") ?: return@mapNotNull null
                val control = s.childText("controlURL") ?: return@mapNotNull null
                DlnaService(type, resolve(base, control) ?: return@mapNotNull null)
            }
            val av = services.firstOrNull { it.type.contains(":AVTransport:") } ?: continue
            return DlnaRenderer(
                udn = device.childText("UDN") ?: location,
                friendlyName = device.childText("friendlyName") ?: "Media player",
                manufacturer = device.childText("manufacturer"),
                modelName = device.childText("modelName"),
                location = location,
                avTransport = av,
                renderingControl = services.firstOrNull { it.type.contains(":RenderingControl:") },
                connectionManager = services.firstOrNull { it.type.contains(":ConnectionManager:") },
            )
        }
        return null
    } }

    fun resolve(base: String, ref: String): String? = runCatching { URL(URL(base), ref.trim()).toString() }.getOrNull()
}

// ---- SOAP --------------------------------------------------------------------------

class UpnpException(val code: Int?, message: String) : IOException(message)

/** UPnP control over SOAP. [open] lets callers bind connections to the Wi-Fi network. */
class SoapClient(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    fun call(service: DlnaService, action: String, args: List<Pair<String, String>> = emptyList()): Map<String, String> {
        val body = envelope(service.type, action, args).toByteArray(Charsets.UTF_8)
        val conn = open(URL(service.controlUrl))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"${service.type}#$action\"")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val fault = runCatching { parseFault(text) }.getOrNull()
                throw UpnpException(fault?.first, fault?.second ?: "$action failed (HTTP $code)")
            }
            return parseResponse(text, action)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            args.forEach { (k, v) -> append('<').append(k).append('>').append(Xml.escape(v)).append("</").append(k).append('>') }
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }

        fun parseResponse(xml: String, action: String): Map<String, String> {
            if (xml.isBlank()) return emptyMap()
            with(Xml) {
                val response = parse(xml).all("${action}Response").firstOrNull() ?: return emptyMap()
                val nodes = response.childNodes
                return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                    .associate { it.local to it.textContent.orEmpty().trim() }
            }
        }

        fun parseFault(xml: String): Pair<Int?, String>? {
            with(Xml) {
                val doc = parse(xml)
                val code = doc.all("errorCode").firstOrNull()?.textContent?.trim()?.toIntOrNull()
                val description = doc.all("errorDescription").firstOrNull()?.textContent?.trim()
                if (code == null && description == null) return null
                return code to friendlyFault(code, description)
            }
        }

        fun friendlyFault(code: Int?, description: String?): String = when (code) {
            701 -> "the TV wasn't ready for that yet"
            704, 714 -> "the TV doesn't support this format"
            710 -> "the TV can't seek in this media"
            716 -> "the TV couldn't find the media"
            401 -> "the TV doesn't support that action"
            else -> description?.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: "the TV reported error $code"
        }
    }
}

// ---- Time & metadata ------------------------------------------------------------------

object DlnaTime {
    /** Parses `H+:MM:SS[.F+]`. Returns null for blank / `NOT_IMPLEMENTED`. */
    fun parse(value: String?): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || !v[0].isDigit()) return null
        val parts = v.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secPart = parts[2].substringBefore('/')
        val s = secPart.toDoubleOrNull() ?: return null
        return (h * 3_600_000) + (m * 60_000) + (s * 1000).toLong()
    }

    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }
}

object Didl {
    fun item(title: String, url: String, mime: String, kind: MediaKind, durationMs: Long?): String {
        val upnpClass = when (kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
        }
        val duration = durationMs?.let { " duration=\"${DlnaTime.format(it)}.000\"" }.orEmpty()
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"mood-0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${Xml.escape(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${Xml.escape(mime)}:${DLNA_PROFILE}\"$duration>${Xml.escape(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private const val DLNA_PROFILE = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
}

data class DlnaPosition(val positionMs: Long?, val durationMs: Long?, val trackUri: String?)

/** Typed AVTransport / RenderingControl / ConnectionManager actions for one renderer. */
class DlnaControl(val renderer: DlnaRenderer, private val soap: SoapClient) {
    private val av = renderer.avTransport
    private val instance = "InstanceID" to "0"

    fun setUri(url: String, metadata: String) {
        soap.call(av, "SetAVTransportURI", listOf(instance, "CurrentURI" to url, "CurrentURIMetaData" to metadata))
    }

    fun play() { soap.call(av, "Play", listOf(instance, "Speed" to "1")) }
    fun pause() { soap.call(av, "Pause", listOf(instance)) }
    fun stop() { soap.call(av, "Stop", listOf(instance)) }
    fun seek(ms: Long) { soap.call(av, "Seek", listOf(instance, "Unit" to "REL_TIME", "Target" to DlnaTime.format(ms))) }

    fun transportState(): String? = soap.call(av, "GetTransportInfo", listOf(instance))["CurrentTransportState"]

    fun position(): DlnaPosition {
        val r = soap.call(av, "GetPositionInfo", listOf(instance))
        return DlnaPosition(DlnaTime.parse(r["RelTime"]), DlnaTime.parse(r["TrackDuration"]), r["TrackURI"])
    }

    /** Actions the TV says are available now; null if it doesn't implement the query. */
    fun transportActions(): Set<String>? = try {
        soap.call(av, "GetCurrentTransportActions", listOf(instance))["Actions"]
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    } catch (e: UpnpException) {
        null
    }

    /** MIME types the TV declares it can render; null if it doesn't say. */
    fun sinkFormats(): Set<String>? {
        val cm = renderer.connectionManager ?: return null
        val sink = soap.call(cm, "GetProtocolInfo")["Sink"].orEmpty()
        return CompatibilityChecker.parseProtocolInfo(sink).takeIf { it.isNotEmpty() }
    }

    fun volume(): Int? = renderer.renderingControl?.let {
        soap.call(it, "GetVolume", listOf(instance, "Channel" to "Master"))["CurrentVolume"]?.toIntOrNull()
    }

    fun setVolume(value: Int) {
        renderer.renderingControl?.let {
            soap.call(it, "SetVolume", listOf(instance, "Channel" to "Master", "DesiredVolume" to value.coerceIn(0, 100).toString()))
        }
    }

    fun muted(): Boolean? = renderer.renderingControl?.let {
        soap.call(it, "GetMute", listOf(instance, "Channel" to "Master"))["CurrentMute"]?.let { v -> v == "1" || v.equals("true", true) }
    }

    fun setMuted(muted: Boolean) {
        renderer.renderingControl?.let {
            soap.call(it, "SetMute", listOf(instance, "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"))
        }
    }
}
