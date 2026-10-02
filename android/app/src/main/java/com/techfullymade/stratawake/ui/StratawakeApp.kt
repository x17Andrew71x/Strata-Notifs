package com.techfullymade.stratawake.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techfullymade.stratawake.R
import com.techfullymade.stratawake.ui.theme.Basalt
import com.techfullymade.stratawake.ui.theme.FossilMint
import com.techfullymade.stratawake.ui.theme.Sand

@Composable
fun StratawakeApp() {
  Scaffold(containerColor = Basalt) { insets ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(insets)
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.today_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = stringResource(R.string.today_subtitle),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
      )
      StrataPreview()
      Text(
        text = stringResource(R.string.permission_explanation),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.enable_notification_access))
      }
    }
  }
}

@Composable
private fun StrataPreview() {
  val description = stringResource(R.string.strata_preview_description)
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
        .padding(24.dp)
        .semantics { contentDescription = description },
    ) {
      val bandHeight = size.height / 8f
      val bands = listOf(
        Color(0xFF1D2421),
        Color(0xFF25302B),
        Color(0xFF314039),
        Color(0xFF3A4942),
        Color(0xFF29332F),
        Color(0xFF38463F),
        Color(0xFF202824),
        Color(0xFF161B19),
      )
      bands.forEachIndexed { index, color ->
        drawRoundRect(
          color = color,
          topLeft = Offset(0f, index * bandHeight),
          size = Size(size.width, bandHeight + 2f),
          cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
        )
      }
      drawCircle(
        color = Sand,
        radius = size.minDimension * 0.16f,
        center = center,
      )
      drawCircle(
        color = Basalt,
        radius = size.minDimension * 0.095f,
        center = center,
      )
      drawCircle(
        color = FossilMint,
        radius = size.minDimension * 0.035f,
        center = center,
      )
    }
  }
}
