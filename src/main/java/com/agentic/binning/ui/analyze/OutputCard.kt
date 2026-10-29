package com.agentic.binning.ui.analyze

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.LocalGlassTokens

/** The agent output card: streamed description with a caret, the clip's action timeline, tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputCard(output: OutputState, modifier: Modifier = Modifier) {
    val tokens = LocalGlassTokens.current
    GlassSurface(modifier, shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    SectionLabel("Description", Modifier.weight(1f))
                    Text(output.speed, color = tokens.sub, style = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum"))
                }
                when {
                    output.notice != null ->
                        Text(output.notice, color = tokens.sub, fontSize = 14.sp, lineHeight = 1.45.em)
                    output.failure != null ->
                        Text(output.failure, color = tokens.warn, fontSize = 14.sp, lineHeight = 1.45.em)
                    else -> Description(output.description, output.typing)
                }
            }
            if (output.actions.isNotEmpty()) {
                Column {
                    SectionLabel("Action", Modifier.padding(bottom = 6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        output.actions.forEach { ActionLine(it) }
                    }
                }
            }
            if (output.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    output.tags.forEach { TagChip(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { heading() },
        color = LocalGlassTokens.current.sub,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em,
    )
}

/** The description so far, followed by a blinking caret while it is still streaming. */
@Composable
private fun Description(text: String, typing: Boolean) {
    val tokens = LocalGlassTokens.current
    val annotated = buildAnnotatedString {
        append(text)
        if (typing) appendInlineContent(CARET_ID, "▍")
    }
    val inline = if (!typing) emptyMap() else mapOf(
        CARET_ID to InlineTextContent(Placeholder(4.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)) {
            val visible by rememberInfiniteTransition(label = "caret").animateFloat(
                initialValue = 1f,
                targetValue = 1f,
                // CSS `steps(1)` blink: on for half a second, off for half a second.
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1000
                        1f at 0
                        1f at 499
                        0f at 500
                        0f at 999
                    },
                ),
                label = "caretBlink",
            )
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .fillMaxSize()
                    .graphicsLayer { alpha = visible }
                    .background(tokens.accent),
            )
        },
    )
    Text(annotated, color = tokens.text, fontSize = 14.sp, lineHeight = 1.45.em, inlineContent = inline)
}

@Composable
private fun ActionLine(row: ActionRow) {
    val tokens = LocalGlassTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.time != null) {
            Text(
                row.time,
                modifier = Modifier
                    .alignByBaseline()
                    .background(tokens.chip, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                color = tokens.sub,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
        }
        Text(
            row.text,
            modifier = Modifier.alignByBaseline(),
            color = tokens.text,
            fontSize = 13.sp,
            lineHeight = 1.35.em,
        )
    }
}

@Composable
private fun TagChip(tag: String) {
    val tokens = LocalGlassTokens.current
    Text(
        "#$tag",
        modifier = Modifier
            .background(tokens.tagBg, CircleShape)
            .border(1.dp, tokens.border, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = tokens.tagText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

private const val CARET_ID = "caret"
