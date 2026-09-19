package com.rakshika.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rakshika.app.ui.navigation.RakshikaNavHost
import com.rakshika.app.ui.screens.splash.SplashScreen
import com.rakshika.app.ui.theme.RakshikaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app theme is light-only, so keep dark system-bar icons even when the device is in
        // dark mode (the default would draw white icons over the light map/screens).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            RakshikaTheme {
                // Saveable so a rotation mid-app doesn't replay the splash.
                var splashDone by rememberSaveable { mutableStateOf(false) }
                Crossfade(targetState = splashDone, animationSpec = tween(400), label = "splash") { done ->
                    if (done) RakshikaNavHost() else SplashScreen(onFinished = { splashDone = true })
                }
            }
        }
    }
}
