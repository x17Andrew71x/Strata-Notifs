package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryItemDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(item: InventoryItemEntity)

  @Query(
    """
      SELECT * FROM inventory_items
      WHERE consumed_by_mutation_id IS NULL
      ORDER BY created_at_epoch_millis DESC, item_id ASC
    """,
  )
  fun observeAllActive(): Flow<List<InventoryItemEntity>>

  @Query(
    """
      SELECT * FROM inventory_items
      WHERE item_id IN (:itemIds)
        AND consumed_by_mutation_id IS NULL
    """,
  )
  suspend fun activeByIds(itemIds: List<String>): List<InventoryItemEntity>

  @Query("SELECT * FROM inventory_items WHERE item_id = :itemId")
  suspend fun getByItemId(itemId: String): InventoryItemEntity?

  @Query(
    """
      UPDATE inventory_items
      SET is_locked = :locked
      WHERE item_id = :itemId
        AND revealed_at_epoch_millis IS NOT NULL
        AND consumed_by_mutation_id IS NULL
        AND is_locked != :locked
    """,
  )
  suspend fun setLockedIfRevealed(itemId: String, locked: Boolean): Int

  @Query("UPDATE inventory_items SET is_locked = :locked WHERE item_id = :itemId")
  suspend fun setLocked(itemId: String, locked: Boolean): Int

  @Query(
    """
      UPDATE inventory_items
      SET revealed_at_epoch_millis = :revealedAtEpochMillis
      WHERE source_specimen_id = :specimenId
        AND revealed_at_epoch_millis IS NULL
        AND :revealedAtEpochMillis >= created_at_epoch_millis
    """,
  )
  suspend fun markSourceRevealedIfUnrevealed(specimenId: String, revealedAtEpochMillis: Long): Int

  @Query(
    """
      UPDATE inventory_items
      SET consumed_by_mutation_id = :mutationId
      WHERE item_id IN (:itemIds)
        AND consumed_by_mutation_id IS NULL
    """,
  )
  suspend fun consumeActive(itemIds: List<String>, mutationId: String): Int
}
