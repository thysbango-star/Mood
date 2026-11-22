package com.rovian.mood.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * MoodPlay's film library from the Internet Archive's Feature Films collection —
 * public-domain and openly licensed films, served free by a non-profit.
 *
 * Uses the archive's keyless APIs: `advancedsearch.php` for rows and search
 * (titles, years, descriptions, audience ratings) and `/metadata/{id}` for the
 * playable files and full-size artwork, fetched when a film is opened.
 */
class InternetArchiveCatalog : MovieCatalog {
    override val providerName = "Internet Archive"

    private val detailsCache = ConcurrentHashMap<String, Movie>()

    private data class RowSpec(val title: String, val query: String, val sort: String)

    private val base = "collection:(feature_films) AND mediatype:(movies)"
    private val rows = listOf(
        RowSpec("Most Watched", base, "downloads desc"),
        RowSpec("Highest Rated", "$base AND num_reviews:[5 TO 100000]", "avg_rating desc"),
        RowSpec("Film Noir", "$base AND subject:(\"film noir\")", "downloads desc"),
        RowSpec("Comedy", "$base AND subject:(comedy)", "downloads desc"),
        RowSpec("Science Fiction", "$base AND subject:(\"science fiction\")", "downloads desc"),
        RowSpec("Horror", "$base AND subject:(horror)", "downloads desc"),
        RowSpec("Westerns", "$base AND subject:(western)", "downloads desc"),
    )

    override suspend fun home(): CatalogHome = coroutineScope {
        val loaded = rows.map { spec -> async { runCatching { CatalogRow(spec.title, query(spec.query, spec.sort, 24)) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
            .filter { it.movies.isNotEmpty() }
        if (loaded.isEmpty()) throw IOException("The Internet Archive didn't respond.")
        // Hero carousel: well-rated, popular films, with their full-size artwork.
        val candidates = (loaded.firstOrNull { it.title == "Highest Rated" }?.movies.orEmpty() + loaded.first().movies)
            .distinctBy { it.id }
            .take(6)
        val heroes = candidates.map { m -> async { runCatching { details(m) }.getOrDefault(m) } }.awaitAll()
        CatalogHome(featured = heroes.first(), rows = loaded, heroes = heroes)
    }

    override suspend fun search(query: String): List<Movie> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace(Regex("[\\\\\"():\\[\\]{}^~*?!+-]"), " ").trim()
        if (escaped.isEmpty()) return emptyList()
        return query("$base AND (title:($escaped) OR subject:($escaped))", "downloads desc", 40)
    }

    override suspend fun details(movie: Movie): Movie {
        if (movie.detailed) return movie
        detailsCache[movie.id]?.let { return it }
        val json = getJson("https://archive.org/metadata/${enc(movie.id)}")
        val files = json.optJSONArray("files") ?: JSONArray()
        val server = "https://archive.org/download/${enc(movie.id)}/"
        val streams = mutableListOf<MovieStream>()
        var bestImage: Pair<Long, String>? = null
        var lengthSeconds: Double? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val lower = name.lowercase()
            val format = f.optString("format").lowercase()
            if (lower.endsWith(".mp4")) {
                val height = f.optString("height").toIntOrNull()
                streams += MovieStream(server + name.split('/').joinToString("/") { enc(it) }, "video/mp4", height)
                f.optString("length").toDoubleOrNull()?.let { lengthSeconds = maxOf(lengthSeconds ?: 0.0, it) }
            } else if ((format == "jpeg" || format == "png" || lower.endsWith(".jpg") || lower.endsWith(".png")) &&
                !lower.contains("thumb") && !lower.contains("__ia_")
            ) {
                val size = f.optString("size").toLongOrNull() ?: 0
                if (bestImage == null || size > bestImage!!.first) bestImage = size to server + enc(name)
            }
        }
        val meta = json.optJSONObject("metadata")
        val full = movie.copy(
            synopsis = meta?.let { clean(it.flatString("description")) }?.ifBlank { null } ?: movie.synopsis,
            backdropUrl = bestImage?.second ?: movie.backdropUrl,
            runtimeMinutes = movie.runtimeMinutes ?: lengthSeconds?.let { (it / 60).toInt() }?.takeIf { it > 0 },
            // Best quality first; MoodFit then picks what the receiver can play.
            streams = streams.distinctBy { it.url }.sortedByDescending { it.height ?: 0 },
            detailed = true,
        )
        detailsCache[movie.id] = full
        return full
    }

    private suspend fun query(q: String, sort: String, rowsWanted: Int): List<Movie> {
        val fields = listOf("identifier", "title", "year", "description", "avg_rating", "num_reviews", "subject", "runtime")
        val url = buildString {
            append("https://archive.org/advancedsearch.php?q=").append(enc(q))
            fields.forEach { append("&fl%5B%5D=").append(it) }
            append("&sort%5B%5D=").append(enc(sort))
            append("&rows=").append(rowsWanted).append("&page=1&output=json")
        }
        val docs = getJson(url).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i -> docs.optJSONObject(i)?.let(::toMovie) }
    }

    private fun toMovie(d: JSONObject): Movie? {
        val id = d.optString("identifier").ifBlank { return null }
        val title = d.flatString("title").ifBlank { return null }
        val subjects = d.flatList("subject")
        val genre = subjects.map { it.trim() }
            .firstOrNull { s -> s.isNotBlank() && s.length < 24 && !s.contains("feature", true) && !s.contains("public domain", true) }
            ?.replaceFirstChar { it.uppercase() }
            .orEmpty()
        val poster = "https://archive.org/services/img/${enc(id)}"
        return Movie(
            id = id,
            title = title,
            year = d.flatString("year").take(4).toIntOrNull() ?: 0,
            genre = genre,
            runtimeMinutes = parseRuntime(d.flatString("runtime")),
            synopsis = clean(d.flatString("description")).take(600),
            posterUrl = poster,
            backdropUrl = poster,
            streams = emptyList(),
            credit = "Internet Archive · free to watch · archive.org/details/$id",
            rating = d.optString("avg_rating").toDoubleOrNull(),
            ratingCount = d.optString("num_reviews").toIntOrNull(),
            detailed = false,
        )
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MoodPlay/0.3 (Android; non-profit)")
            if (conn.responseCode !in 200..299) throw IOException("Internet Archive returned ${conn.responseCode}")
            JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** "1:23:45", "83 min", "83" → minutes. */
        internal fun parseRuntime(raw: String): Int? {
            val r = raw.trim().lowercase()
            if (r.isEmpty()) return null
            if (r.contains(':')) {
                val p = r.split(':').mapNotNull { it.trim().toIntOrNull() }
                return when (p.size) {
                    3 -> p[0] * 60 + p[1]
                    2 -> p[0]
                    else -> null
                }?.takeIf { it > 0 }
            }
            return Regex("(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..600 }
        }

        /** Strips HTML the archive often stores in descriptions. */
        internal fun clean(html: String): String = html
            .replace(Regex("(?i)<br\\s*/?>|</p>"), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun JSONObject.flatString(key: String): String = when (val v = opt(key)) {
            is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
            null -> ""
            else -> v.toString()
        }

        private fun JSONObject.flatList(key: String): List<String> = when (val v = opt(key)) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> v.split(';')
            null -> emptyList()
            else -> listOf(v.toString())
        }
    }
}
