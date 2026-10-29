package com.agentic.binning

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.agentic.binning.ui.theme.AgenticTheme
import com.agentic.binning.ui.theme.LocalGlassTokens
import com.agentic.binning.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgenticTheme(ThemeMode.System) {
                Box(Modifier.fillMaxSize().background(LocalGlassTokens.current.bg))
            }
        }
    }
}
