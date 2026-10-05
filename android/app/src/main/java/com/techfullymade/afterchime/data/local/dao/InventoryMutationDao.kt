package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.InventoryMutationEntity

@Dao
interface InventoryMutationDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(mutation: InventoryMutationEntity)

  @Query("SELECT * FROM inventory_mutations WHERE mutation_id = :mutationId")
  suspend fun getByMutationId(mutationId: String): InventoryMutationEntity?
}
