package com.techfullymade.stratawake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.techfullymade.stratawake.ui.StratawakeApp
import com.techfullymade.stratawake.ui.theme.StratawakeTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      StratawakeTheme {
        StratawakeApp()
      }
    }
  }
}
