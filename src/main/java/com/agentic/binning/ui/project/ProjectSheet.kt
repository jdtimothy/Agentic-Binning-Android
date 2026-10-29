package com.agentic.binning.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.theme.LocalGlassTokens

/** What the sheet shows. Task 7 adds registration progress and summary fields. */
data class ProjectSheetState(
    val projects: List<ProjectRef>,
    val current: ProjectRef?,
    val createError: String?,
)

/**
 * Glass bottom sheet for creating, switching and adding media to projects. The add buttons only
 * report taps; the host launches the system pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSheet(
    state: ProjectSheetState,
    onCreate: (String) -> Unit,
    onSwitch: (ProjectRef) -> Unit,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.current) { name = "" }
    val canAddMedia = state.current != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = shape,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        // The sheet lives in its own window, so the screen's backdrop blur cannot reach it:
        // an opaque base under a strong glass fill instead.
        CompositionLocalProvider(LocalHazeState provides null) {
            Box(Modifier.background(tokens.bg, shape)) {
                GlassSurface(Modifier.fillMaxWidth(), shape = shape, strong = true) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Section("Create project") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Project name", color = tokens.sub) },
                                isError = state.createError != null,
                                supportingText = state.createError?.let { error ->
                                    { Text(error, color = tokens.warn) }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = tokens.text,
                                    unfocusedTextColor = tokens.text,
                                    cursorColor = tokens.accent,
                                    focusedBorderColor = tokens.accent,
                                    unfocusedBorderColor = tokens.border,
                                    errorBorderColor = tokens.warn,
                                    errorTextColor = tokens.text,
                                    errorCursorColor = tokens.warn,
                                ),
                            )
                            PillButton("Create", enabled = name.isNotBlank(), primary = true) { onCreate(name) }
                        }

                        Section("Add media") {
                            PillButton("Add a folder", enabled = canAddMedia, onClick = onAddFolder)
                            PillButton("Pick photos and clips", enabled = canAddMedia, onClick = onAddFiles)
                            if (!canAddMedia) {
                                Text("Create or choose a project first.", color = tokens.sub, fontSize = 13.sp)
                            }
                        }

                        Section("Your projects") {
                            if (state.projects.isEmpty()) {
                                Text("No projects yet.", color = tokens.sub, fontSize = 14.sp)
                            } else {
                                state.projects.forEach { ref ->
                                    ProjectRow(ref, isCurrent = ref.path == state.current?.path) { onSwitch(ref) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val tokens = LocalGlassTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = tokens.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PillButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (primary) tokens.accent else tokens.chip)
            .border(1.dp, if (primary) Color.Transparent else tokens.border, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) tokens.onAccent else tokens.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ProjectRow(ref: ProjectRef, isCurrent: Boolean, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) tokens.tagBg else tokens.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ref.name,
            modifier = Modifier.weight(1f),
            color = tokens.text,
            fontSize = 16.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (isCurrent) {
            Spacer(Modifier.width(12.dp))
            Text("Current", color = tokens.tagText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
