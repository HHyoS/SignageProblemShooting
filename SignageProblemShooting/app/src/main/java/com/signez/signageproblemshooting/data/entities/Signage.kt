package com.signez.signageproblemshooting.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.signez.signageproblemshooting.data.Converters
import java.sql.Blob

@Entity(tableName = "signages",
    foreignKeys = [
        ForeignKey(
            entity = Cabinet::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onUpdate = ForeignKey.CASCADE,
        )
    ])
@TypeConverters(Converters::class)
data class Signage(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var name: String,
    var heightCabinetNumber: Int,
    var widthCabinetNumber: Int,
    var height: Double,
    var width: Double,
    var modelId: Long,
    var repImg: ByteArray? = null
)