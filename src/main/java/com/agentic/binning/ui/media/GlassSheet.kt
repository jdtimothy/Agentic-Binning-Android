package com.agentic.binning.ui.media

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.theme.GlassTokens
import com.agentic.binning.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch

// The owner's glass (mockup review, Nov 3): in dark mode a sheet is the lighter glass and the cards inside
// it the darker; a dialog with no cards is the darker glass itself. Light mode keeps the regular glass.
internal val GlassTokens.sheetGlass: Color get() = if (isDark) Color(0x8C484E60) else glass
internal val GlassTokens.dialogGlass: Color get() = if (isDark) well else glass
internal val GlassTokens.well: Color get() = if (isDark) Color(0x750A0C12) else chip
internal val GlassTokens.wellWarn: Color get() = if (isDark) Color(0x9E34240C) else warnSoft

/** A card of choices on a sheet: the darker glass in dark mode, a field's fill in light. */
internal val GlassTokens.choiceCard: Color get() = if (isDark) well else field
internal val GlassTokens.sheetScrim: Color get() = if (isDark) Color(0x80000000) else Color(0x57141A28)

private val SheetTop = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
private val DialogShape = RoundedCornerShape(28.dp)

/** How far a sheet is dragged before letting go closes it. */
private val DismissDrag = 120.dp

/**
 * The frame of every sheet in the app. It draws in the screen's own window, not a dialog's: this phone has
 * no cross-window blur (`ro.surface_flinger.supports_background_blur=0`), and Haze only blurs its own
 * window, so only here can the glass really blur what is behind it. A dialog's window gave Back, the scrim,
 * swipe to close and room for the keyboard for free, so this does them. Call it last in the screen's root:
 * it fills the screen and draws over what came before.
 */
@Composable
internal fun GlassSheet(onDismiss: () -> Unit, tall: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val dismissAt = with(LocalDensity.current) { DismissDrag.toPx() }
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    BackHandler(onBack = onDismiss)
    ResizeForKeyboard()

    fun settle() {
        if (drag.value > dismissAt) onDismiss() else scope.launch { drag.animateTo(0f) }
    }

    // Past the top of the sheet's own scrolling, a downward swipe drags the sheet.
    val pullDown = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0 || drag.value <= 0f) return Offset.Zero
                val used = maxOf(available.y, -drag.value)
                scope.launch { drag.snapTo(drag.value + used) }
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0) return Offset.Zero
                scope.launch { drag.snapTo(drag.value + available.y) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val held = drag.value > 0f
                if (held) settle()
                return if (held) available else Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        AnimatedVisibility(
            appear,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 2 } + fadeIn(),
        ) {
            Box(Modifier.statusBarsPadding().padding(top = 8.dp).offset { IntOffset(0, drag.value.toInt()) }) {
                GlassSurface(Modifier.fillMaxWidth(), shape = SheetTop, fill = tokens.sheetGlass) {
                    Column(
                        Modifier
                            .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier)
                            .nestedScroll(pullDown)
                            .draggable(
                                rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                                Orientation.Vertical,
                                onDragStopped = { settle() },
                            )
                            .navigationBarsPadding(),
                    ) {
                        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).align(Alignment.CenterHorizontally).size(40.dp, 5.dp).background(tokens.track, CircleShape))
                        content()
                    }
                }
            }
        }
    }
}

/**
 * A dialog in the screen's own window, for the same reason as [GlassSheet]. [onDismiss] is what Back and a
 * tap outside do; null means it is answered only with its buttons (a copy in progress, say).
 */
@Composable
internal fun GlassDialog(onDismiss: (() -> Unit)?, outsideDismisses: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalGlassTokens.current
    BackHandler(enabled = onDismiss != null) { onDismiss?.invoke() }
    Box(Modifier.fillMaxSize()) {
        Scrim(if (outsideDismisses) onDismiss else null)
        GlassSurface(
            Modifier.align(Alignment.Center).padding(horizontal = 28.dp).fillMaxWidth(),
            shape = DialogShape,
            fill = tokens.dialogGlass,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp), content = content)
        }
    }
}

@Composable
private fun Scrim(onTap: (() -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(LocalGlassTokens.current.sheetScrim)
            // Takes the tap either way: what is behind a sheet can't be touched.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onTap?.invoke() }),
    )
}

/** While a sheet shows, the keyboard shrinks the screen rather than panning it, so Save stays above it. */
@Composable
private fun ResizeForKeyboard() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        val before = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { before?.let { window.setSoftInputMode(it) } }
    }
}
