package com.techfullymade.afterchime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.preferencesDataStore
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.data.catalog.DataStoreWorldCatalog
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.sharing.ShareUseCase
import com.techfullymade.afterchime.ui.AfterchimeApp
import com.techfullymade.afterchime.ui.museum.MuseumScreen
import com.techfullymade.afterchime.ui.museum.MuseumViewModel
import com.techfullymade.afterchime.ui.museum.MissingSpecimenDetailScreen
import com.techfullymade.afterchime.ui.museum.SpecimenDetailScreen
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import com.techfullymade.afterchime.ui.today.TodayScreen
import com.techfullymade.afterchime.ui.today.TodayViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val applicationState get() = application as AfterchimeApplication
  private val museumViewModel: MuseumViewModel by viewModels {
    MuseumViewModel.factory(applicationState.museumRepository)
  }
  private val todayViewModel: TodayViewModel by viewModels {
    TodayViewModel.factory(applicationState.formationRepository)
  }
  private val userPreferences by lazy { applicationState.userPreferences }
  private val worldCatalog by lazy { DataStoreWorldCatalog(applicationContext.worldCatalogDataStore) }
  private val developmentWorldsEnabled get() = isDevelopmentWorldsEnabled(BuildConfig.FLAVOR)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val museumState by museumViewModel.uiState.collectAsState()
      val preferences by userPreferences.values.collectAsState(initial = null)
      val worldState by worldCatalog.state.collectAsState(initial = DevelopmentWorldState())
      val preferenceScope = rememberCoroutineScope()
      preferences?.let { loadedPreferences ->
        AfterchimeTheme(
          reduceMotion = loadedPreferences.reduceMotionEnabled,
          highContrast = loadedPreferences.highContrastEnabled,
        ) {
          AfterchimeApp(
            onEnableNotificationAccess = { openNotificationAccessSettings() },
            preferences = loadedPreferences,
            onPreferencesChanged = { updated -> preferenceScope.launch { userPreferences.update { updated } } },
            onOnboardingComplete = {
              preferenceScope.launch { userPreferences.update { it.copy(onboardingComplete = true) } }
            },
            worldState = worldState,
            developmentControlsEnabled = developmentWorldsEnabled,
            onOwnWorldForDevelopment = { world ->
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.ownForDevelopment(world) }
            },
            onSelectWorld = { world ->
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.select(world) }
            },
            onResetDevelopmentWorlds = {
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.resetDevelopmentOwnership() }
            },
            todayContent = {
              MainActivityTodayContent(
                viewModel = todayViewModel,
                preferences = loadedPreferences,
                onEnableNotificationAccess = { openNotificationAccessSettings() },
                modifier = Modifier.fillMaxSize(),
              )
            },
            museumContent = { openSpecimen ->
              MuseumScreen(
                state = museumState,
                world = if (developmentWorldsEnabled) worldState.selectedWorld else World.PRIMEVAL_STRATA,
                onTierFilterSelected = museumViewModel::selectTierFilter,
                onSpecimenSelected = { specimenId ->
                  museumViewModel.selectSpecimen(specimenId)
                  openSpecimen(specimenId)
                },
                onCombineSpecimenSelected = museumViewModel::toggleCombineSpecimen,
                onCancelCombine = museumViewModel::cancelCombine,
                onReviewCombine = museumViewModel::reviewCombine,
                onDismissCombineConfirmation = museumViewModel::dismissCombineConfirmation,
                onConfirmCombine = museumViewModel::confirmCombine,
              )
            },
            museumDetailContent = { specimenId, onBack ->
              val specimen = museumState.specimens.firstOrNull { candidate ->
                candidate.id == specimenId && candidate.revealedAtEpochMillis != null
              }
              if (specimen == null) {
                MissingSpecimenDetailScreen(onBack = onBack)
              } else {
                SpecimenDetailScreen(
                  specimen = specimen,
                  world = if (developmentWorldsEnabled) worldState.selectedWorld else World.PRIMEVAL_STRATA,
                  onBack = onBack,
                  onSetLocked = { locked -> museumViewModel.setSpecimenLocked(specimen.id, locked) },
                  onBeginCombine = {
                    museumViewModel.beginCombine(specimen.id)
                    onBack()
                  },
                  onShareSpecimen = {
                    ShareUseCase(this@MainActivity).share(
                      specimen,
                      if (developmentWorldsEnabled) worldState.selectedWorld else World.PRIMEVAL_STRATA,
                    )
                  },
                )
              }
            },
          )
        }
      }
    }
  }

  internal fun openNotificationAccessSettings(): Boolean = try {
    startActivity(
      Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        ComponentName(this, StrataNotificationListenerService::class.java).flattenToString(),
      ),
    )
    true
  } catch (_: ActivityNotFoundException) {
    try {
      startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      true
    } catch (_: ActivityNotFoundException) {
      try {
        startActivity(Intent(Settings.ACTION_SETTINGS))
        true
      } catch (_: ActivityNotFoundException) {
        false
      }
    }
  }

  internal fun openAppDetailsSettings(): Boolean = try {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    true
  } catch (_: ActivityNotFoundException) {
    try {
      startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
      true
    } catch (_: ActivityNotFoundException) {
      false
    }
  }
}

internal fun isDevelopmentWorldsEnabled(flavor: String): Boolean = flavor == "dev"

@Composable
internal fun MainActivityTodayContent(
  viewModel: TodayViewModel,
  preferences: UserPreferences,
  onEnableNotificationAccess: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  TodayScreen(
    state = state,
    onEnableNotificationAccess = onEnableNotificationAccess,
    onReveal = { viewModel.reveal() },
    reduceMotion = preferences.reduceMotionEnabled,
    modifier = modifier,
  )
}

private val Context.worldCatalogDataStore by preferencesDataStore(name = "world_catalog")
