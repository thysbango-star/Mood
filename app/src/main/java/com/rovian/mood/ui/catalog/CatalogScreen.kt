package com.rovian.mood.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.catalog.CatalogHome
import com.rovian.mood.catalog.Movie
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors

fun Movie.meta(): String = "$year • $genre • $runtimeMinutes minutes"

@Composable
fun CatalogScreen(
    home: CatalogHome?,
    error: String?,
    watchLabel: String,
    contentStart: Dp,
    onWatch: (Movie) -> Unit,
    onInfo: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (home == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Loading…", color = MoodColors.InkSecondary, fontSize = 16.sp)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Hero(home.featured, watchLabel, contentStart, onWatch, onInfo) }
        home.rows.forEach { row ->
            item {
                Text(
                    row.title,
                    color = MoodColors.Ink,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = contentStart, top = 28.dp, bottom = 14.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(start = contentStart, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(row.movies, key = { row.title + it.id }) { movie -> PosterCard(movie, 250.dp) { onInfo(movie) } }
                }
            }
        }
    }
}

@Composable
private fun Hero(movie: Movie, watchLabel: String, contentStart: Dp, onWatch: (Movie) -> Unit, onInfo: (Movie) -> Unit) {
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        RemoteImage(movie.backdropUrl, null, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.45f to Color.Transparent,
                    1f to MoodColors.Night,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Transparent),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = contentStart, end = 24.dp, bottom = 12.dp).widthIn(max = 520.dp),
        ) {
            Text(movie.title, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
            Spacer(Modifier.height(8.dp))
            Text(movie.meta(), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                movie.synopsis,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassPill(onClick = { onWatch(movie) }) {
                    MoodIcon(Glyph.Home, size = 22.dp, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(watchLabel, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                GlassPill(onClick = { onInfo(movie) }) {
                    Text("Info", color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
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

@Composable
fun PosterCard(movie: Movie, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(role = Role.Button, onClick = onClick)) {
        RemoteImage(
            movie.posterUrl,
            movie.title,
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(movie.title, color = MoodColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("${movie.year} • ${movie.runtimeMinutes} min", color = MoodColors.InkSecondary, fontSize = 13.sp)
    }
}

@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Movie>,
    contentStart: Dp,
    onOpen: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().padding(start = contentStart, end = 24.dp, top = 24.dp)) {
        Text("Search", color = MoodColors.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoodIcon(Glyph.Search, size = 20.dp, tint = MoodColors.InkSecondary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Movies, genres, years", color = MoodColors.InkSecondary, fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = MoodColors.Ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(MoodColors.Orange),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (query.isNotBlank() && results.isEmpty()) {
            Text("No movies match “$query”.", color = MoodColors.InkSecondary, fontSize = 15.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            items(results, key = { it.id }) { movie -> PosterCard(movie, 400.dp) { onOpen(movie) } }
        }
    }
}
