package com.agentic.binning.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.LocalGlassTokens

/** Select mode's bottom bar (mockup state 4): Move to category and Remove from project, off with nothing ticked. */
@Composable
fun SelectionBar(enabled: Boolean, vm: ProjectMediaViewModel, modifier: Modifier = Modifier) {
    val tokens = LocalGlassTokens.current
    GlassSurface(modifier.fillMaxWidth().height(66.dp), shape = RoundedCornerShape(30.dp)) {
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BulkButton(MediaIcons.FolderMove, "Move to category", tokens.accent, tokens.onAccent, enabled, Modifier.weight(1f), vm::onBulkMove)
            BulkButton(MediaIcons.Remove, "Remove from project", tokens.chipStrong, tokens.danger, enabled, Modifier.weight(1f), vm::onBulkRemove)
        }
    }
}

@Composable
private fun BulkButton(
    icon: ImageVector,
    label: String,
    fill: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        // 13 sp, a little under the mockup's 14 px: "Remove from project" must fit half a 390 dp phone.
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}
