package com.rovian.mood.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.library.LibraryItem
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.MoodGradient
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.formatTime
import com.rovian.mood.ui.components.liquidGlass

@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    importing: Boolean,
    contentStart: Dp,
    thumbnail: suspend (LibraryItem) -> Bitmap?,
    onAdd: () -> Unit,
    onOpen: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = contentStart, end = contentStart, bottom = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.statusBarsPadding().padding(top = 76.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your Library", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (items.isEmpty()) "Your own videos, music and photos" else "${items.size} item${if (items.size == 1) "" else "s"} · stays on this phone",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                    )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(role = Role.Button, onClick = onAdd)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(if (importing) "Adding…" else "Add", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(onAdd) }
        }
        items(items, key = { it.id }) { item -> LibraryCard(item, thumbnail) { onOpen(item) } }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(28.dp)).clickable(role = Role.Button, onClick = onAdd).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(76.dp).height(30.dp).clip(RoundedCornerShape(50)).background(MoodGradient))
        Spacer(Modifier.height(18.dp))
        Text("Bring your own media", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add videos, music and photos from this phone. Watch them in the Mood player — Enhance sharpens " +
                "them when they're shown bigger than they were filmed — and send them to your TV.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun LibraryCard(item: LibraryItem, thumbnail: suspend (LibraryItem) -> Bitmap?, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.id) { value = thumbnail(item) }
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                .shadow(12.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A211D), Color(0xFF15110F)))),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (thumb == null) {
                MoodIcon(
                    when (item.kind) { MediaKind.VIDEO -> Glyph.Video; MediaKind.AUDIO -> Glyph.Music; MediaKind.IMAGE -> Glyph.Photo },
                    Modifier.align(Alignment.Center),
                    size = 34.dp,
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            item.durationMs?.takeIf { item.kind != MediaKind.IMAGE }?.let {
                Text(
                    formatTime(it),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).liquidGlass(RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.kind.label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}
