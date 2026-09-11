package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_meta")
data class DictionaryMeta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val priority: Int,
    val enabled: Boolean = true,
    /**
     * True for dictionaries shipped inside the app (#43 — the vendored pitch
     * dictionary). Built-ins are not user-editable: the manager hides them and
     * there is no way to delete them, so their presence is app state rather
     * than user state.
     */
    val builtIn: Boolean = false
)
