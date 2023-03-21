/*
 * Copyright (C) 2022 The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kgh.signezprototype.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kgh.signezprototype.data.entities.Cabinet
import com.kgh.signezprototype.data.entities.Item
import kotlinx.coroutines.flow.Flow

/**
 * Database access object to access the Inventory database
 */
@Dao
interface CabinetDao {

    @Query("SELECT * from cabinets ORDER BY name ASC")
    fun getAllCabinets(): Flow<List<Cabinet>>

    @Query("SELECT * from cabinets WHERE id = :id")
    fun getCabinet(id: Long): Flow<Cabinet>

    // Specify the conflict strategy as IGNORE, when the user tries to add an
    // existing Item into the database Room ignores the conflict.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cabinet: Cabinet)

    @Update
    suspend fun update(cabinet: Cabinet)

    @Delete
    suspend fun delete(cabinet: Cabinet)

    @Query("""
        SELECT cabinets.* FROM cabinets
        INNER JOIN signages ON cabinets.id = signages.modelId
        WHERE signages.id = :signageId
    """)
    suspend fun getCabinetBySignageId(signageId: Long): Cabinet
}
