package com.agentic.binning.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.agentic.binning.ui.theme.LocalGlassTokens

private val TileShape = RoundedCornerShape(18.dp)
private val BadgeShape = RoundedCornerShape(8.dp)
private val BadgeBg = Color(0x8C000000) // rgba(0,0,0,0.55), both themes
private val FailedBg = Color(0xFFFFB547)
private val FailedText = Color(0xFF2A1A00)

/**
 * One grid tile: the picture with its badges (duration, Waiting / Failed, no locks: Q16), the name,
 * and while searching where it matched. In select mode a tick sits top left and a ticked tile shrinks
 * inside an accent ring.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(tile: TileUi, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    val scale by animateFloatAsState(if (tile.selected) 0.9f else 1f, tween(140), label = "tile scale")
    val request = remember(tile.image, tile.imageIsVideo) {
        ImageRequest.Builder(context)
            .data(tile.image)
            .apply { if (tile.imageIsVideo) decoderFactory(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    val where = when (tile.badge) {
        TileBadge.Waiting -> "waiting to be categorized"
        TileBadge.Failed -> "failed"
        TileBadge.Analyzing -> "analyzing"
        null -> null
    }
    val label = listOfNotNull(tile.name, if (tile.isVideo) "video" + (tile.duration?.let { " $it" } ?: "") else "photo", where)
        .joinToString(", ")
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (selecting) selected = tile.selected
            }
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(scale)
                .then(if (tile.selected) Modifier.border(3.dp, tokens.accent, TileShape) else Modifier)
                .clip(TileShape)
                .background(tokens.chip)
                .alpha(if (tile.badge == TileBadge.Analyzing) 0.55f else 1f),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selecting) Tick(tile.selected)
            when (tile.badge) {
                TileBadge.Waiting -> Badge(MediaIcons.Clock, "Waiting", BadgeBg, Color.White, Alignment.BottomStart)
                TileBadge.Failed -> Badge(MediaIcons.Alert, "Failed", FailedBg, FailedText, Alignment.BottomStart)
                TileBadge.Analyzing -> Badge(null, "Analyzing…", BadgeBg, Color.White, Alignment.BottomStart)
                null -> Unit
            }
            if (tile.isVideo) Badge(MediaIcons.Play, tile.duration ?: "Video", BadgeBg, Color.White, Alignment.BottomEnd, iconSize = 9)
        }
        Column {
            Text(
                tile.name,
                modifier = Modifier.padding(horizontal = 2.dp),
                color = tokens.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tile.matchedText?.let {
                Text(it, modifier = Modifier.padding(horizontal = 2.dp), color = tokens.sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BoxScope.Tick(on: Boolean) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(7.dp)
            .size(24.dp)
            .background(if (on) tokens.accent else Color(0x40000000), CircleShape)
            .border(2.dp, if (on) tokens.accent else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(MediaIcons.Check, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun BoxScope.Badge(
    icon: ImageVector?,
    text: String,
    background: Color,
    content: Color,
    align: Alignment,
    iconSize: Int = 11,
) {
    Row(
        Modifier
            .align(align)
            .padding(6.dp)
            .background(background, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize.dp))
        Text(text, color = content, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
