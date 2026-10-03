package com.techfullymade.afterchime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.techfullymade.afterchime.ui.AfterchimeApp
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AfterchimeTheme {
        AfterchimeApp()
      }
    }
  }
}
