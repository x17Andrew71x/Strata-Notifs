package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier

/** Immutable, privacy-safe output of the generator for one sealed specimen. */
@Entity(
  tableName = "specimen_outputs",
  foreignKeys = [
    ForeignKey(
      entity = SpecimenEntity::class,
      parentColumns = ["specimen_id"],
      childColumns = ["specimen_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class SpecimenOutputEntity(
  @PrimaryKey
  @ColumnInfo(name = "specimen_id")
  val specimenId: String,
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
) {
  init {
    require(specimenId.isNotBlank())
    require(hueDegrees in 0..359)
    require(strataCount in 4..16)
    require(inclusionDensityPercent in 0..100)
    require(reliefPercent in 30..100)
    require(rotationDegrees in 0..359)
  }
}
