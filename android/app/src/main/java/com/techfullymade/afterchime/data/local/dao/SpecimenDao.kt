package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity

@Dao
interface SpecimenDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(specimen: SpecimenEntity)

  @Query("SELECT * FROM specimens WHERE anchored_local_date = :localDate")
  suspend fun getByAnchoredLocalDate(localDate: String): SpecimenEntity?
}