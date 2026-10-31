package com.agentic.binning.ui.analyze

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.model.DownloadState
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlin.math.roundToInt

/** Glass pill: model chip (download state), project chip, settings button. */
@Composable
fun TopBar(
    header: HeaderState,
    onOpenProjectSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
        Row(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelChip(header.model)
            ProjectChip(header.projectName, header.itemCount, onOpenProjectSheet, Modifier.weight(1f))
            SettingsButton(onOpenSettings)
        }
    }
}

@Composable
private fun ModelChip(state: DownloadState?) {
    val tokens = LocalGlassTokens.current
    val (sub, subColor) = when (state) {
        DownloadState.Ready -> "NPU" to tokens.sub
        is DownloadState.Downloading -> "${(state.fraction * 100).roundToInt()}%" to tokens.accent
        is DownloadState.Failed -> "Download failed" to tokens.warn
        DownloadState.Absent -> "Not downloaded" to tokens.sub
        null -> "" to tokens.sub
    }
    val label = "Model: LFM3-VL-2B" + if (sub.isNotEmpty()) ", $sub" else ""
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(tokens.chip)
            .semantics { contentDescription = label },
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AnalyzeIcons.Model, contentDescription = null, tint = tokens.text, modifier = Modifier.size(20.dp))
            Column {
                Text("LFM3-VL-2B", color = tokens.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)
                Text(sub, color = subColor, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
            }
        }
        if (state is DownloadState.Downloading) {
            // Download progress along the chip's bottom edge; matchParentSize keeps the chip's width.
            Box(Modifier.matchParentSize().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(tokens.accent, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ProjectChip(name: String?, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalGlassTokens.current
    val description = if (name == null) "Create project" else "Project: $name, $count items. Add media or switch project"
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(tokens.chip)
            .clickable(role = Role.Button, onClickLabel = "Open projects", onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AnalyzeIcons.Folder, contentDescription = null, tint = tokens.text, modifier = Modifier.size(20.dp))
        Text(
            name ?: "Create project",
            modifier = Modifier.weight(1f),
            color = tokens.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (name != null) {
            Text("$count", color = tokens.sub, style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tokens.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AnalyzeIcons.Settings, contentDescription = null, tint = tokens.text, modifier = Modifier.size(20.dp))
    }
}
