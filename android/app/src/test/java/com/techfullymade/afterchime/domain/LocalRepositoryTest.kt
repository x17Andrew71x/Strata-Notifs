package com.techfullymade.afterchime.domain

import androidx.room.Room
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRepositoryTest {
  private lateinit var database: AfterchimeDatabase
  private lateinit var formationRepository: LocalFormationRepository
  private lateinit var museumRepository: LocalMuseumRepository

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    formationRepository = LocalFormationRepository(database)
    museumRepository = LocalMuseumRepository(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `formation emits safe visual layers from local Room data`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val sourceToken = "a".repeat(64)
    database.listenerAccessStateDao().upsert(
      ListenerAccessStateEntity(
        localDate = localDate.toString(),
        activeAtEpochMillis = 1_759_593_600_000L,
        disconnectedAtEpochMillis = null,
        revokedAtEpochMillis = null,
        latestState = ListenerAccessState.ACTIVE,
        updatedAtEpochMillis = 1_759_593_600_000L,
      ),
    )
    database.reducedNotificationDao().insert(
      ReducedNotificationEntity(
        id = "event-1",
        occurredAtEpochMillis = 1_759_597_200_000L,
        localDate = localDate.toString(),
        localHour = 9,
        category = CoarseNotificationCategory.EMAIL,
        sourceToken = sourceToken,
        sourceColourRgb = 0x123456,
      ),
    )

    val formation = formationRepository.observe(localDate).first()

    assertEquals(FormationObservation.Active, formation.observation)
    assertEquals(
      listOf(
        FormationLayer(
          localHour = 9,
          category = CoarseNotificationCategory.EMAIL,
          sourceColourRgb = 0x123456,
        ),
      ),
      formation.layers,
    )
    assertNull(formation.sealedSpecimen)
    assertFalse(formation.toString().contains(sourceToken))
    assertFalse(FormationLayer::class.java.declaredFields.any { it.name.contains("token", ignoreCase = true) })
  }

  @Test
  fun `museum flow and reveal remain local idempotent and entity-free`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-02")
    val specimenId = "specimen-1"
    database.daySummaryDao().insert(
      DaySummaryEntity(
        localDate = localDate.toString(),
        timezoneOffsetMinutes = 0,
        observationState = ObservationState.OBSERVED,
        generatorVersion = 1,
        createdAtEpochMillis = 1_759_507_200_000L,
      ),
    )
    database.specimenDao().insert(
      SpecimenEntity(
        specimenId = specimenId,
        anchoredLocalDate = localDate.toString(),
        generatorVersion = 1,
        createdAtEpochMillis = 1_759_507_200_000L,
        revealedAtEpochMillis = null,
      ),
    )
    database.specimenOutputDao().insert(
      SpecimenOutputEntity(
        specimenId = specimenId,
        family = Family.GEODE,
        tier = Tier.EXCEPTIONAL,
        hueDegrees = 143,
        strataCount = 11,
        inclusionDensityPercent = 72,
        reliefPercent = 63,
        rotationDegrees = 217,
      ),
    )
    database.inventoryItemDao().insert(
      inventoryItem(
        specimenId = specimenId,
        localDate = localDate,
        tier = Tier.EXCEPTIONAL,
        createdAtEpochMillis = 1_759_507_200_000L,
      ),
    )

    val initialMuseum = museumRepository.specimens.first()
    val initialFormation = formationRepository.observe(localDate).first()

    assertEquals(1, initialMuseum.size)
    assertEquals(specimenId, initialMuseum.single().id)
    assertEquals(Family.GEODE, initialMuseum.single().family)
    assertNull(initialMuseum.single().revealedAtEpochMillis)
    assertEquals(initialMuseum.single(), initialFormation.sealedSpecimen)
    assertEquals(FormationObservation.SealedObserved, initialFormation.observation)

    assertEquals(
      RevealResult.Revealed(revealedAtEpochMillis = 1_759_593_600_000L),
      formationRepository.reveal(specimenId, 1_759_593_600_000L),
    )
    assertEquals(
      RevealResult.AlreadyRevealed(revealedAtEpochMillis = 1_759_593_600_000L),
      formationRepository.reveal(specimenId, 1_759_680_000_000L),
    )

    val revealed = museumRepository.specimens.first().single()
    assertEquals(1_759_593_600_000L, revealed.revealedAtEpochMillis)
    assertTrue(MuseumSpecimen::class.java.declaredFields.none { it.type.name.contains("Entity") })
  }

  @Test
  fun `revealed specimen lock is durable, local and idempotent`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val specimenId = "specimen-lockable"
    database.specimenDao().insert(
      SpecimenEntity(
        specimenId = specimenId,
        anchoredLocalDate = localDate.toString(),
        generatorVersion = 1,
        createdAtEpochMillis = 1_759_593_600_000L,
        revealedAtEpochMillis = null,
      ),
    )
    database.specimenOutputDao().insert(
      SpecimenOutputEntity(
        specimenId = specimenId,
        family = Family.GEODE,
        tier = Tier.RARE,
        hueDegrees = 143,
        strataCount = 11,
        inclusionDensityPercent = 72,
        reliefPercent = 63,
        rotationDegrees = 217,
      ),
    )
    database.inventoryItemDao().insert(
      inventoryItem(
        specimenId = specimenId,
        localDate = localDate,
        tier = Tier.RARE,
      ),
    )

    assertEquals(SpecimenLockResult.Unrevealed, museumRepository.setLocked(specimenId, locked = true))
    assertFalse(museumRepository.specimens.first().single().isLocked)

    formationRepository.reveal(specimenId, 1_759_680_000_000L)
    assertEquals(SpecimenLockResult.Changed(true), museumRepository.setLocked(specimenId, locked = true))
    assertTrue(museumRepository.specimens.first().single().isLocked)
    assertTrue(database.specimenDao().getBySpecimenId(specimenId)?.isLocked == true)
    assertEquals(SpecimenLockResult.AlreadySet(true), museumRepository.setLocked(specimenId, locked = true))

    assertEquals(SpecimenLockResult.Changed(false), museumRepository.setLocked(specimenId, locked = false))
    assertFalse(museumRepository.specimens.first().single().isLocked)
    assertFalse(database.specimenDao().getBySpecimenId(specimenId)?.isLocked == true)
  }

  private fun inventoryItem(
    specimenId: String,
    localDate: LocalDate,
    tier: Tier,
    createdAtEpochMillis: Long = 1_759_593_600_000L,
  ) = InventoryItemEntity(
    itemId = specimenId,
    sourceSpecimenId = specimenId,
    anchoredLocalDate = localDate.toString(),
    generatorVersion = 1,
    createdAtEpochMillis = createdAtEpochMillis,
    revealedAtEpochMillis = null,
    collectibleState = CollectibleState.ORDINARY,
    provenanceCount = 1,
    family = Family.GEODE,
    tier = tier,
    hueDegrees = 143,
    strataCount = 11,
    inclusionDensityPercent = 72,
    reliefPercent = 63,
    rotationDegrees = 217,
    consumedByMutationId = null,
  )
}
