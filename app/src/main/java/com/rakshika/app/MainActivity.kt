package com.rakshika.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rakshika.app.ui.navigation.RakshikaNavHost
import com.rakshika.app.ui.theme.RakshikaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RakshikaTheme {
                RakshikaNavHost()
            }
        }
    }
}
