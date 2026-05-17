package com.holopengin.instantjpdict.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Query("SELECT * FROM dictionary WHERE kanji = :text OR reading = :text")
    suspend fun findByText(text: String): List<DictionaryEntry>

    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun getCount(): Int

    @Query("DELETE FROM dictionary")
    suspend fun clearAll()
}
