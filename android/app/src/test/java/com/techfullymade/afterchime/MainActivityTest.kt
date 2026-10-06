package com.techfullymade.afterchime

import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.domain.RevealResult
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import com.techfullymade.afterchime.ui.today.TodayViewModel
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `development world boundary follows flavour regardless of build type`() {
    assertTrue(isDevelopmentWorldsEnabled("dev"))
    assertFalse(isDevelopmentWorldsEnabled("prod"))
  }

  @Test
  fun `activity content tree does not create a web shell`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    assertFalse(containsWebView(activity.window.decorView))
  }

  @Test
  fun `activity uses its explicit native notification access action`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    assertTrue(activity.openNotificationAccessSettings())
    val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, startedIntent?.action)
    assertEquals(
      ComponentName(activity, StrataNotificationListenerService::class.java).flattenToString(),
      startedIntent?.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
    )
  }

  @Test
  fun `native Today reveal action reaches the local formation repository once`() {
    val repository = TestFormationRepository(
      FormationSnapshot(
        localDate = LocalDate.now(),
        observation = FormationObservation.SealedObserved,
        layers = emptyList(),
        sealedSpecimen = testSpecimen(),
      ),
    )
    val preferences = UserPreferences(onboardingComplete = true)
    val viewModel = TodayViewModel(repository)
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = preferences.reduceMotionEnabled) {
        MainActivityTodayContent(
          viewModel = viewModel,
          preferences = preferences,
          onEnableNotificationAccess = {},
        )
      }
    }

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("Reveal specimen").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Reveal specimen").performClick()
    shadowOf(android.os.Looper.getMainLooper()).idle()
    composeRule.waitUntil(timeoutMillis = 5_000) { repository.revealCalls == 1 }
    composeRule.runOnIdle { assertEquals(1, repository.revealCalls) }
  }

  @Test
  fun `persisted reduced motion pauses the real native Today formation`() {
    val repository = TestFormationRepository(
      FormationSnapshot(
        localDate = LocalDate.now(),
        observation = FormationObservation.Active,
        layers = emptyList(),
        sealedSpecimen = null,
      ),
    )
    val preferences = UserPreferences(onboardingComplete = true, reduceMotionEnabled = true)
    val viewModel = TodayViewModel(repository)
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = preferences.reduceMotionEnabled) {
        MainActivityTodayContent(
          viewModel = viewModel,
          preferences = preferences,
          onEnableNotificationAccess = {},
        )
      }
    }

    composeRule.onNodeWithTag("formation-motion-paused").assertExists()
  }

  @Test
  @Suppress("DEPRECATION")
  fun `Android 12 listener starts closed and sensitive notification types remain disabled`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val serviceInfo = activity.packageManager.getServiceInfo(
      ComponentName(activity, StrataNotificationListenerService::class.java),
      PackageManager.GET_META_DATA,
    )

    assertEquals(31, activity.applicationInfo.minSdkVersion)
    assertEquals(
      "",
      serviceInfo.metaData.getString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES),
    )
    val disabledTypes = serviceInfo.metaData
      .getString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES)
      ?.split(",")
      ?.map(String::toInt)
      ?.toSet()
    assertEquals(
      setOf(
        NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS,
        NotificationListenerService.FLAG_FILTER_TYPE_SILENT,
        NotificationListenerService.FLAG_FILTER_TYPE_ONGOING,
      ),
      disabledTypes,
    )
    assertFalse(disabledTypes.orEmpty().contains(NotificationListenerService.FLAG_FILTER_TYPE_ALERTING))
  }
}

private fun containsWebView(view: View): Boolean = when (view) {
  is WebView -> true
  is ViewGroup -> (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
  else -> false
}

private class TestFormationRepository(
  snapshot: FormationSnapshot,
) : FormationRepository {
  private val state = MutableStateFlow(snapshot)
  var revealCalls: Int = 0
    private set

  override fun observe(localDate: LocalDate): Flow<FormationSnapshot> = state

  override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
    revealCalls += 1
    return RevealResult.Revealed(revealedAtEpochMillis)
  }
}

private fun testSpecimen() = MuseumSpecimen(
  id = "local",
  anchoredLocalDate = LocalDate.now().minusDays(1),
  generatorVersion = 1,
  createdAtEpochMillis = 1L,
  revealedAtEpochMillis = null,
  family = Family.AMMONITE,
  tier = Tier.UNCOMMON,
  visual = VisualParameters(
    hueDegrees = 32,
    strataCount = 7,
    inclusionDensityPercent = 30,
    reliefPercent = 42,
    rotationDegrees = 14,
  ),
)
