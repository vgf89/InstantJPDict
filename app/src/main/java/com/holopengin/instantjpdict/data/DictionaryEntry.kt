package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary",
    indices = [
        Index(value = ["kanji"]),
        Index(value = ["reading"])
    ]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val kanji: String,
    val reading: String,
    val definitions: String, // Store as JSON string
    val rules: String,
    val popularity: Int,
    val dictionaryId: Int
)
