package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity

@Dao
interface SpecimenOutputDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(output: SpecimenOutputEntity)

  @Query("SELECT * FROM specimen_outputs WHERE specimen_id = :specimenId")
  suspend fun getBySpecimenId(specimenId: String): SpecimenOutputEntity?
}
