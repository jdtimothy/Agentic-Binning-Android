package com.agentic.binning.ui.permission

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.agentic.binning.ui.glass.AmbientBackdrop
import com.agentic.binning.ui.glass.GlassSurface
import com.agentic.binning.ui.glass.LocalHazeState
import com.agentic.binning.ui.settings.AmbientBlobColors
import com.agentic.binning.ui.theme.LocalGlassTokens
import dev.chrisbanes.haze.HazeState

private const val GATE_PREFS = "storage_gate"
private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

/**
 * Shows [content] once the app holds all files access; until then shows a glass card explaining
 * why it is needed, with a button that opens the system settings page. Access is re-checked every
 * time the app resumes. Once access is held, the notification permission is requested one time.
 */
@Composable
fun StorageAccessGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = Environment.isExternalStorageManager() }

    if (granted) {
        NotificationPermissionRequest(context)
        content()
    } else {
        GateCard(onOpenSettings = { openAllFilesSettings(context) })
    }
}

@Composable
private fun NotificationPermissionRequest(context: Context) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE)
        val alreadyGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted && !prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun openAllFilesSettings(context: Context) {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

@Composable
private fun GateCard(onOpenSettings: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackdrop(AmbientBlobColors, hazeState)
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), strong = true) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Agentic Binning needs access to all files so it can read your media and, later, move it into project folders.",
                            color = tokens.text,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(CircleShape)
                                .background(tokens.accent)
                                .clickable(role = Role.Button, onClick = onOpenSettings),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Allow access to all files",
                                color = tokens.onAccent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
