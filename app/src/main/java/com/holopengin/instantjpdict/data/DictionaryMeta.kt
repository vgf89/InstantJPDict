package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_meta")
data class DictionaryMeta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val priority: Int,
    val enabled: Boolean = true
)
