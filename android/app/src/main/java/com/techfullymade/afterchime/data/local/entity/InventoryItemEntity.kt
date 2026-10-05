package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.techfullymade.afterchime.domain.CollectibleState
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier

/**
 * One local collection item. Daily sealed specimens are mirrored here, while restored pieces have
 * no anchored day and retain only aggregate provenance.
 */
@Entity(
  tableName = "inventory_items",
  foreignKeys = [
    ForeignKey(
      entity = SpecimenEntity::class,
      parentColumns = ["specimen_id"],
      childColumns = ["source_specimen_id"],
      onDelete = ForeignKey.RESTRICT,
    ),
  ],
  indices = [
    Index(value = ["source_specimen_id"], unique = true),
    Index(value = ["consumed_by_mutation_id"]),
  ],
)
data class InventoryItemEntity(
  @PrimaryKey
  @ColumnInfo(name = "item_id")
  val itemId: String,
  @ColumnInfo(name = "source_specimen_id")
  val sourceSpecimenId: String?,
  @ColumnInfo(name = "anchored_local_date")
  val anchoredLocalDate: String?,
  @ColumnInfo(name = "generator_version")
  val generatorVersion: Int,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
  @ColumnInfo(name = "revealed_at_epoch_millis")
  val revealedAtEpochMillis: Long?,
  @ColumnInfo(name = "is_locked", defaultValue = "0")
  val isLocked: Boolean = false,
  @ColumnInfo(name = "collectible_state")
  val collectibleState: CollectibleState,
  @ColumnInfo(name = "provenance_count")
  val provenanceCount: Int,
  @ColumnInfo(name = "family")
  val family: Family,
  @ColumnInfo(name = "tier")
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
  @ColumnInfo(name = "consumed_by_mutation_id")
  val consumedByMutationId: String?,
) {
  init {
    require(itemId.isNotBlank())
    require(sourceSpecimenId == null || sourceSpecimenId == itemId)
    require(generatorVersion > 0)
    require(createdAtEpochMillis >= 0)
    require(revealedAtEpochMillis == null || revealedAtEpochMillis >= createdAtEpochMillis)
    require(provenanceCount > 0)
    require(hueDegrees in 0..359)
    require(strataCount in 4..16)
    require(inclusionDensityPercent in 0..100)
    require(reliefPercent in 30..100)
    require(rotationDegrees in 0..359)
  }
}
