package com.techfullymade.afterchime.domain

import androidx.room.Room
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalCombineRepositoryTest {
  private lateinit var database: AfterchimeDatabase
  private lateinit var repository: LocalMuseumRepository

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    repository = LocalMuseumRepository(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `combine consumes three ordinary items creates restored output and replays idempotently`() = runBlocking {
    insertItems("ordinary-1", "ordinary-2", "ordinary-3")
    val request = CombineRequest(
      mutationId = "combine-ordinary",
      outputItemId = "restored-output",
      inputItemIds = listOf("ordinary-3", "ordinary-1", "ordinary-2"),
      createdAtEpochMillis = 1_759_766_400_000L,
    )

    assertEquals(
      CombineResult.Combined(outputItemId = "restored-output"),
      repository.combine(request),
    )
    assertEquals(
      CombineResult.AlreadyCombined(outputItemId = "restored-output"),
      repository.combine(request),
    )

    val collection = repository.specimens.first()
    assertEquals(1, collection.size)
    assertEquals("restored-output", collection.single().id)
    assertEquals(CollectibleState.RESTORED, collection.single().collectibleState)
    assertEquals(3, collection.single().provenanceCount)
    assertTrue(collection.single().isRestored)
  }

  @Test
  fun `combine rejects locked or mixed inputs without consuming any item`() = runBlocking {
    insertItems("ordinary-1", "ordinary-2", "ordinary-3")
    database.inventoryItemDao().setLocked("ordinary-2", locked = true)

    assertEquals(
      CombineResult.Ineligible(CombineEligibility.LockedInput),
      repository.combine(
        CombineRequest(
          mutationId = "locked-combine",
          outputItemId = "locked-output",
          inputItemIds = listOf("ordinary-1", "ordinary-2", "ordinary-3"),
          createdAtEpochMillis = 1_759_766_400_000L,
        ),
      ),
    )
    assertEquals(setOf("ordinary-1", "ordinary-2", "ordinary-3"), repository.specimens.first().map { it.id }.toSet())

    insertItems("mixed-1", "mixed-2")
    database.inventoryItemDao().insert(item("mixed-3", family = Family.AMBER))
    assertEquals(
      CombineResult.Ineligible(CombineEligibility.NonIdenticalInput),
      repository.combine(
        CombineRequest(
          mutationId = "mixed-combine",
          outputItemId = "mixed-output",
          inputItemIds = listOf("mixed-1", "mixed-2", "mixed-3"),
          createdAtEpochMillis = 1_759_766_400_000L,
        ),
      ),
    )
    assertEquals(
      setOf("ordinary-1", "ordinary-2", "ordinary-3", "mixed-1", "mixed-2", "mixed-3"),
      repository.specimens.first().map { it.id }.toSet(),
    )
  }

  @Test
  fun `output collision rolls back all input consumption`() = runBlocking {
    insertItems("ordinary-1", "ordinary-2", "ordinary-3")
    database.inventoryItemDao().insert(item("existing-output"))

    assertEquals(
      CombineResult.OutputConflict,
      repository.combine(
        CombineRequest(
          mutationId = "failed-combine",
          outputItemId = "existing-output",
          inputItemIds = listOf("ordinary-1", "ordinary-2", "ordinary-3"),
          createdAtEpochMillis = 1_759_766_400_000L,
        ),
      ),
    )
    assertEquals(
      setOf("ordinary-1", "ordinary-2", "ordinary-3", "existing-output"),
      repository.specimens.first().map { it.id }.toSet(),
    )
  }

  @Test
  fun `three restored items combine into one centre piece with nine item provenance`() = runBlocking {
    insertItems(
      "restored-1",
      "restored-2",
      "restored-3",
      state = CollectibleState.RESTORED,
      provenanceCount = 3,
    )

    assertEquals(
      CombineResult.Combined(outputItemId = "centre-output"),
      repository.combine(
        CombineRequest(
          mutationId = "combine-restored",
          outputItemId = "centre-output",
          inputItemIds = listOf("restored-1", "restored-2", "restored-3"),
          createdAtEpochMillis = 1_759_766_400_000L,
        ),
      ),
    )

    val output = repository.specimens.first().single()
    assertEquals(CollectibleState.CENTRE_PIECE, output.collectibleState)
    assertEquals(9, output.provenanceCount)
  }

  private suspend fun insertItems(
    vararg ids: String,
    state: CollectibleState = CollectibleState.ORDINARY,
    provenanceCount: Int = 1,
  ) {
    ids.forEach { id -> database.inventoryItemDao().insert(item(id, state = state, provenanceCount = provenanceCount)) }
  }

  private fun item(
    id: String,
    family: Family = Family.GEODE,
    state: CollectibleState = CollectibleState.ORDINARY,
    provenanceCount: Int = 1,
  ) = InventoryItemEntity(
    itemId = id,
    sourceSpecimenId = null,
    anchoredLocalDate = null,
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_593_600_000L,
    revealedAtEpochMillis = 1_759_593_600_000L,
    isLocked = false,
    collectibleState = state,
    provenanceCount = provenanceCount,
    family = family,
    tier = Tier.RARE,
    hueDegrees = 143,
    strataCount = 11,
    inclusionDensityPercent = 72,
    reliefPercent = 63,
    rotationDegrees = 217,
    consumedByMutationId = null,
  )
}
