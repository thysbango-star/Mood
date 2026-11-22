package com.rovian.mood.catalog

/**
 * Built-in catalogue of openly licensed films (Blender Foundation open movies,
 * Creative Commons Attribution), served from Google's public Cast sample bucket —
 * the same files Google uses in its own Cast samples. Every title is genuinely
 * castable, so the whole find → watch → broadcast flow works today without a
 * content deal. Replace with a licensed partner's [MovieCatalog] for production.
 */
class OpenFilmsCatalog : MovieCatalog {
    override val providerName = "Open films"

    private val bucket = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample"
    private val blender = "© Blender Foundation · CC BY · blender.org"

    private val movies = listOf(
        Movie(
            id = "tears-of-steel",
            title = "Tears of Steel",
            year = 2012,
            genre = "Science fiction",
            runtimeMinutes = 12,
            synopsis = "In a future Amsterdam, a group of warriors and scientists stage a desperate " +
                "last stand against an army of robots — and try to undo a broken heart.",
            posterUrl = "$bucket/images/TearsOfSteel.jpg",
            backdropUrl = "$bucket/images/TearsOfSteel.jpg",
            streams = listOf(MovieStream("$bucket/TearsOfSteel.mp4", "video/mp4", 720)),
            credit = blender,
        ),
        Movie(
            id = "sintel",
            title = "Sintel",
            year = 2010,
            genre = "Fantasy",
            runtimeMinutes = 15,
            synopsis = "A lonely young woman searches the wilds for the baby dragon she once rescued, " +
                "and finds the cost of the journey far greater than she imagined.",
            posterUrl = "$bucket/images/Sintel.jpg",
            backdropUrl = "$bucket/images/Sintel.jpg",
            streams = listOf(MovieStream("$bucket/Sintel.mp4", "video/mp4", 720)),
            credit = blender,
        ),
        Movie(
            id = "big-buck-bunny",
            title = "Big Buck Bunny",
            year = 2008,
            genre = "Animated comedy",
            runtimeMinutes = 10,
            synopsis = "A gentle giant of a rabbit is pushed too far by three bullying rodents, " +
                "and plans a comic, elaborate revenge.",
            posterUrl = "$bucket/images/BigBuckBunny.jpg",
            backdropUrl = "$bucket/images/BigBuckBunny.jpg",
            streams = listOf(
                MovieStream("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "application/x-mpegurl"),
                MovieStream("$bucket/BigBuckBunny.mp4", "video/mp4", 720),
            ),
            credit = blender,
        ),
        Movie(
            id = "elephants-dream",
            title = "Elephants Dream",
            year = 2006,
            genre = "Surreal drama",
            runtimeMinutes = 11,
            synopsis = "Two men explore a vast, shifting machine — one sees wonders, the other sees " +
                "only danger. The first open movie ever made.",
            posterUrl = "$bucket/images/ElephantsDream.jpg",
            backdropUrl = "$bucket/images/ElephantsDream.jpg",
            streams = listOf(MovieStream("$bucket/ElephantsDream.mp4", "video/mp4", 720)),
            credit = blender,
        ),
    )

    override suspend fun home(): CatalogHome = CatalogHome(
        featured = movies.first(),
        rows = listOf(
            CatalogRow("Movies For You", movies),
            CatalogRow("Short & Animated", movies.filter { it.runtimeMinutes <= 11 }),
        ),
    )

    override suspend fun search(query: String): List<Movie> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return movies.filter { m ->
            m.title.lowercase().contains(q) || m.genre.lowercase().contains(q) || m.year.toString() == q
        }
    }
}
