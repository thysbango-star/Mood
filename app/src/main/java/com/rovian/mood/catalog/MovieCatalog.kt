package com.rovian.mood.catalog

import com.rovian.mood.cast.CastMedia
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.Compatibility
import com.rovian.mood.cast.CompatibilityChecker
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.MediaSource
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.ReceiverKind

/** One playable rendition of a movie. */
data class MovieStream(
    val url: String,
    val mimeType: String,
    /** Vertical resolution for fixed renditions; null for adaptive (HLS/DASH) streams. */
    val height: Int? = null,
) {
    val adaptive: Boolean get() = mimeType == "application/x-mpegurl" || mimeType == "application/dash+xml"
}

data class Movie(
    val id: String,
    val title: String,
    val year: Int,
    val genre: String,
    val runtimeMinutes: Int,
    val synopsis: String,
    val posterUrl: String,
    val backdropUrl: String,
    val streams: List<MovieStream>,
    /** Licence/credit line the provider requires to be shown. */
    val credit: String,
)

data class CatalogRow(val title: String, val movies: List<Movie>)

data class CatalogHome(val featured: Movie, val rows: List<CatalogRow>)

/**
 * Where MoodPlay's movies come from. The UI and casting never depend on a
 * specific host: a licensed partner catalogue drops in by implementing this
 * (typically backed by the partner's API, returning stream URLs they authorise
 * for casting).
 */
interface MovieCatalog {
    val providerName: String
    suspend fun home(): CatalogHome
    suspend fun search(query: String): List<Movie>
}

/**
 * MoodFit — Mood's screen-aware stream selection.
 *
 * With media casting the TV fetches and decodes the stream itself, so the
 * phone isn't in the video path and adds no lag. What Mood *can* control is
 * which stream the TV gets:
 *  - An adaptive stream (HLS/DASH) when the receiver plays it: the TV then picks
 *    the quality that fits its own screen and connection, continuously.
 *  - Otherwise the best fixed rendition the receiver declares it can play,
 *    capped to what that class of screen can show.
 */
object MoodFit {
    data class Choice(val stream: MovieStream, val explanation: String)

    fun choose(movie: Movie, endpoint: ReceiverEndpoint): Choice? = ranked(movie, endpoint).firstOrNull()

    /** Every stream the receiver may play, best first — so a dead stream can fall back to the next. */
    fun ranked(movie: Movie, endpoint: ReceiverEndpoint): List<Choice> {
        val out = mutableListOf<Choice>()
        var remaining = movie
        while (true) {
            val next = chooseOne(remaining, endpoint) ?: break
            out += next
            remaining = remaining.copy(streams = remaining.streams - next.stream)
            if (remaining.streams.isEmpty()) break
        }
        return out
    }

    private fun chooseOne(movie: Movie, endpoint: ReceiverEndpoint): Choice? {
        val playable = movie.streams.filter {
            CompatibilityChecker.check(endpoint, MediaKind.VIDEO, it.mimeType) !is Compatibility.Unsupported
        }
        if (playable.isEmpty()) return null
        val confirmed = playable.filter {
            CompatibilityChecker.check(endpoint, MediaKind.VIDEO, it.mimeType) is Compatibility.Supported
        }.ifEmpty { playable }

        confirmed.firstOrNull { it.adaptive && (endpoint.protocol == CastProtocol.GOOGLE_CAST || confirmed.all { s -> s.adaptive }) }
            ?.let { return Choice(it, "Adaptive stream — ${endpoint.name} picks the best quality for its screen and connection.") }

        val cap = when (endpoint.kind) {
            ReceiverKind.TV, ReceiverKind.STREAMER -> 2160
            else -> 1080
        }
        val fixed = confirmed.filter { !it.adaptive }
        val best = fixed.filter { (it.height ?: 0) <= cap }.maxByOrNull { it.height ?: 0 }
            ?: fixed.minByOrNull { it.height ?: Int.MAX_VALUE }
            ?: confirmed.first()
        val label = best.height?.let { "${it}p" } ?: "best available"
        return Choice(best, "Sending the $label version ${endpoint.name} reports it can play.")
    }

    fun toCastMedia(movie: Movie, stream: MovieStream) = CastMedia(
        kind = MediaKind.VIDEO,
        title = movie.title,
        mimeType = stream.mimeType,
        source = MediaSource.Remote(stream.url),
        durationMs = movie.runtimeMinutes * 60_000L,
        subtitle = "${movie.year} · ${movie.genre}",
        artworkUrl = movie.backdropUrl,
    )
}
