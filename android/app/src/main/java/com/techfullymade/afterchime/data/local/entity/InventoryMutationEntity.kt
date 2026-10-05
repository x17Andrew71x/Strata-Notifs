package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable idempotency and provenance record for one explicit local restoration operation.
 * It contains opaque local item identifiers only; notification-derived material never enters it.
 */
@Entity(
  tableName = "inventory_mutations",
  indices = [Index(value = ["output_item_id"], unique = true)],
)
data class InventoryMutationEntity(
  @PrimaryKey
  @ColumnInfo(name = "mutation_id")
  val mutationId: String,
  @ColumnInfo(name = "output_item_id")
  val outputItemId: String,
  @ColumnInfo(name = "first_input_item_id")
  val firstInputItemId: String,
  @ColumnInfo(name = "second_input_item_id")
  val secondInputItemId: String,
  @ColumnInfo(name = "third_input_item_id")
  val thirdInputItemId: String,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
) {
  init {
    require(mutationId.isNotBlank())
    require(outputItemId.isNotBlank())
    require(listOf(firstInputItemId, secondInputItemId, thirdInputItemId).all(String::isNotBlank))
    require(setOf(firstInputItemId, secondInputItemId, thirdInputItemId).size == 3)
    require(createdAtEpochMillis >= 0)
  }

  val inputItemIds: List<String>
    get() = listOf(firstInputItemId, secondInputItemId, thirdInputItemId)
}
