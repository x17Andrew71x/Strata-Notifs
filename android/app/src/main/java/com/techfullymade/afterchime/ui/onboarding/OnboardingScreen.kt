package com.techfullymade.afterchime.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing

@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("onboarding-screen")
      .verticalScroll(rememberScrollState())
      .padding(AfterchimeSpacing.screenHorizontal),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.onboarding_consent), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onComplete, modifier = Modifier.testTag("onboarding-continue")) {
      Text(stringResource(R.string.onboarding_continue))
    }
  }
}
