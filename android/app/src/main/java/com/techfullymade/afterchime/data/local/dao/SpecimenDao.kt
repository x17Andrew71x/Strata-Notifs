package com.techfullymade.afterchime.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import kotlinx.coroutines.flow.Flow

/** Internal Room projection; domain repositories map this before it reaches UI callers. */
data class StoredSpecimenWithOutput(
  @ColumnInfo(name = "specimen_id")
  val specimenId: String,
  @ColumnInfo(name = "anchored_local_date")
  val anchoredLocalDate: String,
  @ColumnInfo(name = "generator_version")
  val generatorVersion: Int,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
  @ColumnInfo(name = "revealed_at_epoch_millis")
  val revealedAtEpochMillis: Long?,
  val family: Family,
  val tier: Tier,
  @ColumnInfo(name = "hue_degrees")
  val hueDegrees: Int,
  @ColumnInfo(name = "strata_count")
  val strataCount: Int,
  @ColumnInfo(name = "inclusion_density_percent")
  val inclusionDensityPercent: Int,
  @ColumnInfo(name = "relief_percent")
  val reliefPercent: Int,
  @ColumnInfo(name = "rotation_degrees")
  val rotationDegrees: Int,
)

@Dao
interface SpecimenDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(specimen: SpecimenEntity)

  @Query("SELECT * FROM specimens WHERE anchored_local_date = :localDate")
  suspend fun getByAnchoredLocalDate(localDate: String): SpecimenEntity?

  @Query("SELECT * FROM specimens WHERE specimen_id = :specimenId")
  suspend fun getBySpecimenId(specimenId: String): SpecimenEntity?

  @Query(
    """
      SELECT
        specimens.specimen_id,
        specimens.anchored_local_date,
        specimens.generator_version,
        specimens.created_at_epoch_millis,
        specimens.revealed_at_epoch_millis,
        specimen_outputs.family,
        specimen_outputs.tier,
        specimen_outputs.hue_degrees,
        specimen_outputs.strata_count,
        specimen_outputs.inclusion_density_percent,
        specimen_outputs.relief_percent,
        specimen_outputs.rotation_degrees
      FROM specimens
      INNER JOIN specimen_outputs ON specimen_outputs.specimen_id = specimens.specimen_id
      WHERE specimens.anchored_local_date = :localDate
    """,
  )
  fun observeWithOutputForAnchoredLocalDate(localDate: String): Flow<StoredSpecimenWithOutput?>

  @Query(
    """
      SELECT
        specimens.specimen_id,
        specimens.anchored_local_date,
        specimens.generator_version,
        specimens.created_at_epoch_millis,
        specimens.revealed_at_epoch_millis,
        specimen_outputs.family,
        specimen_outputs.tier,
        specimen_outputs.hue_degrees,
        specimen_outputs.strata_count,
        specimen_outputs.inclusion_density_percent,
        specimen_outputs.relief_percent,
        specimen_outputs.rotation_degrees
      FROM specimens
      INNER JOIN specimen_outputs ON specimen_outputs.specimen_id = specimens.specimen_id
      ORDER BY specimens.anchored_local_date DESC, specimens.specimen_id ASC
    """,
  )
  fun observeAllWithOutput(): Flow<List<StoredSpecimenWithOutput>>

  @Query(
    """
      UPDATE specimens
      SET revealed_at_epoch_millis = :revealedAtEpochMillis
      WHERE specimen_id = :specimenId
        AND revealed_at_epoch_millis IS NULL
        AND :revealedAtEpochMillis >= created_at_epoch_millis
    """,
  )
  suspend fun markRevealedIfUnrevealed(specimenId: String, revealedAtEpochMillis: Long): Int

  @Query("SELECT anchored_local_date FROM specimens WHERE anchored_local_date < :exclusiveLocalDate")
  suspend fun anchoredLocalDatesBefore(exclusiveLocalDate: String): List<String>
}