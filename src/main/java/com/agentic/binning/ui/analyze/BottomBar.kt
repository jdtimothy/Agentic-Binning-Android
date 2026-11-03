package com.agentic.binning.ui.analyze

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.LocalGlassTokens

private const val DISABLED_ALPHA = 0.45f

/** Stop, Pause/Resume and the Project Media button (on while a project is open). */
@Composable
fun BottomBar(
    controls: ControlsState,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    canOpenMedia: Boolean,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(30.dp)) {
        Row(
            Modifier.fillMaxSize().padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(AnalyzeIcons.Stop, "Stop analysis", controls.canStop, onStop)
            RoundButton(
                if (controls.showResume) AnalyzeIcons.Play else AnalyzeIcons.Pause,
                if (controls.showResume) "Resume analysis" else "Pause analysis",
                controls.canToggle,
                onTogglePause,
            )
            ProjectMediaButton(controls.mediaCount, canOpenMedia, onOpenMedia, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .size(46.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(tokens.chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.text, modifier = Modifier.size(16.dp))
    }
}

/** Opens the Project Media screen, with the project's item count. */
@Composable
private fun ProjectMediaButton(count: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalGlassTokens.current
    // The mockup's badge: the on-accent colour, faint (dark text on light accent, or white on dark).
    val badge = tokens.onAccent.copy(alpha = if (tokens.onAccent.luminance() > 0.5f) 0.22f else 0.16f)
    Row(
        modifier
            .height(46.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(23.dp))
            .background(tokens.accent)
            .semantics { contentDescription = "Project Media, $count items" }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AnalyzeIcons.Grid, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(18.dp))
        Text("Project Media", color = tokens.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "$count",
            modifier = Modifier
                .background(badge, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = tokens.onAccent,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }
}
