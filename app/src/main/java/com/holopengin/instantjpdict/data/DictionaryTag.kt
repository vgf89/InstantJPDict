package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_tag")
data class DictionaryTag(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val category: String,
    val order: Int,
    val notes: String,
    val popularity: Int,
    val dictionaryId: Int
)
