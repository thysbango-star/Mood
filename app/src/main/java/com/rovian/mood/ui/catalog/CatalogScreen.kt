package com.rovian.mood.ui.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.catalog.CatalogHome
import com.rovian.mood.catalog.Movie
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.MoodGradient
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.delay
import java.util.Locale

fun Movie.meta(): String =
    listOfNotNull(year.takeIf { it > 0 }?.toString(), genre.ifBlank { null }, runtimeMinutes?.let { "$it min" })
        .joinToString(" · ")

fun Movie.ratingLabel(): String? = rating?.takeIf { it > 0 }?.let { String.format(Locale.US, "★ %.1f", it) }

@Composable
fun CatalogScreen(
    home: CatalogHome?,
    error: String?,
    contentStart: Dp,
    posterWidth: Dp,
    onWatch: (Movie) -> Unit,
    onInfo: (Movie) -> Unit,
    onChooseScreen: (Movie) -> Unit,
    onHeroChange: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (home == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Finding films…", color = MoodColors.InkSecondary, fontSize = 16.sp)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 64.dp)) {
        item { HeroCarousel(home.heroes, contentStart, onWatch, onInfo, onChooseScreen, onHeroChange) }
        home.rows.forEach { row ->
            item(key = "title-" + row.title) {
                Row(
                    Modifier.padding(start = contentStart, top = 30.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    MoodIcon(Glyph.ChevronRight, size = 18.dp, tint = Color.White.copy(alpha = 0.8f))
                }
            }
            item(key = "row-" + row.title) {
                LazyRow(
                    contentPadding = PaddingValues(start = contentStart, end = contentStart),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(row.movies, key = { row.title + it.id }) { movie -> PosterCard(movie, posterWidth) { onInfo(movie) } }
                }
            }
        }
    }
}

@Composable
private fun HeroCarousel(
    heroes: List<Movie>,
    contentStart: Dp,
    onWatch: (Movie) -> Unit,
    onInfo: (Movie) -> Unit,
    onChooseScreen: (Movie) -> Unit,
    onHeroChange: (Movie) -> Unit,
) {
    val pager = rememberPagerState { heroes.size }
    LaunchedEffect(pager.currentPage) {
        heroes.getOrNull(pager.currentPage)?.let(onHeroChange)
        if (heroes.size > 1) {
            delay(9_000)
            pager.animateScrollToPage((pager.currentPage + 1) % heroes.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(600.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val movie = heroes[page]
            Box(Modifier.fillMaxSize()) {
                RemoteImage(movie.backdropUrl, null, Modifier.fillMaxSize())
                // Fade the artwork into the ambient background rather than into flat black.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.3f to Color.Transparent,
                            0.62f to Color.Black.copy(alpha = 0.25f),
                            1f to Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.75f to Color.Transparent)))
                HeroText(movie, contentStart)
            }
        }
        val current = heroes.getOrNull(pager.currentPage) ?: return@Box
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = contentStart, end = contentStart, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Primary: solid white. Secondary: one glass capsule holding two actions.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                    .clickable(role = Role.Button) { onWatch(current) }
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoodIcon(Glyph.Play, size = 20.dp, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Watch", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.liquidGlass(RoundedCornerShape(50)).height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.fillMaxHeight().clickable(role = Role.Button) { onChooseScreen(current) }.padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Watch on a TV" },
                    contentAlignment = Alignment.Center,
                ) { MoodIcon(Glyph.Screen, size = 22.dp, tint = Color.White) }
                Box(Modifier.width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.25f)))
                Box(
                    Modifier.fillMaxHeight().clickable(role = Role.Button) { onInfo(current) }.padding(horizontal = 16.dp)
                        .semantics { contentDescription = "More info" },
                    contentAlignment = Alignment.Center,
                ) { MoodIcon(Glyph.Info, size = 22.dp, tint = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            if (heroes.size > 1) CapsuleDots(heroes.size, pager.currentPage)
        }
    }
}

@Composable
private fun HeroText(movie: Movie, contentStart: Dp) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = contentStart, end = contentStart, bottom = 84.dp).widthIn(max = 620.dp),
        ) {
            Text(
                movie.title,
                color = Color.White,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                movie.ratingLabel()?.let {
                    Text(it, color = MoodColors.Orange, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("  ·  ", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                Text(movie.meta(), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (movie.synopsis.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    movie.synopsis,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Page indicator in Mood's language: the active page is the gradient capsule. */
@Composable
private fun CapsuleDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = i == current
            val w by animateDpAsState(if (active) 26.dp else 7.dp, label = "dot")
            Box(
                Modifier.width(w).height(7.dp).clip(RoundedCornerShape(50))
                    .then(if (active) Modifier.background(MoodGradient) else Modifier.background(Color.White.copy(alpha = 0.45f))),
            )
        }
    }
}

@Composable
fun GlassPill(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .liquidGlass(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Portrait poster with soft depth, title and rating beneath. */
@Composable
fun PosterCard(movie: Movie, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(role = Role.Button, onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .shadow(14.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            RemoteImage(movie.posterUrl, movie.title, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
            movie.ratingLabel()?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .liquidGlass(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(movie.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(movie.year.takeIf { it > 0 }?.toString(), movie.genre.ifBlank { null }).joinToString(" · "),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Movie>,
    searching: Boolean,
    contentStart: Dp,
    onOpen: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().padding(start = contentStart, end = contentStart, top = 76.dp)) {
        Text("Search", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 15.dp).animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoodIcon(Glyph.Search, size = 20.dp, tint = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Films, genres, years", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(MoodColors.Orange),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (searching) Box(Modifier.size(width = 30.dp, height = 12.dp).clip(RoundedCornerShape(50)).background(MoodGradient))
        }
        Spacer(Modifier.height(22.dp))
        if (query.isNotBlank() && results.isEmpty() && !searching) {
            Text("No films match “$query”.", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(bottom = 64.dp),
        ) {
            items(results, key = { it.id }) { movie -> PosterCard(movie, 400.dp) { onOpen(movie) } }
        }
    }
}
