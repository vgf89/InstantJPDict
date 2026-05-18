package com.holopengin.instantjpdict.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Query("""
        SELECT d.* FROM dictionary d 
        JOIN dictionary_meta m ON d.dictionaryId = m.id 
        WHERE d.kanji = :text OR d.reading = :text 
        ORDER BY m.priority ASC, d.popularity DESC
    """)
    suspend fun findByText(text: String): List<DictionaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDictionary(dictionary: DictionaryMeta): Long

    @Query("SELECT * FROM dictionary_meta ORDER BY priority ASC")
    suspend fun getAllDictionaries(): List<DictionaryMeta>

    @Query("DELETE FROM dictionary_meta WHERE id = :dictionaryId")
    suspend fun deleteDictionary(dictionaryId: Int)

    @Query("DELETE FROM dictionary WHERE dictionaryId = :dictionaryId")
    suspend fun deleteEntriesForDictionary(dictionaryId: Int)

    @Query("UPDATE dictionary_meta SET name = :name WHERE id = :dictionaryId")
    suspend fun updateName(dictionaryId: Int, name: String)

    @Query("UPDATE dictionary_meta SET priority = :priority WHERE id = :dictionaryId")
    suspend fun updatePriority(dictionaryId: Int, priority: Int)

    @Query("SELECT MAX(priority) FROM dictionary_meta")
    suspend fun getMaxPriority(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<DictionaryTag>)

    @Query("SELECT notes FROM dictionary_tag WHERE name = :tagName AND dictionaryId = :dictionaryId")
    suspend fun getTagNotes(tagName: String, dictionaryId: Int): String?

    @Query("SELECT notes FROM dictionary_tag WHERE name IN (:tagNames)")
    suspend fun getNotesForTags(tagNames: List<String>): List<String>

    @Query("SELECT * FROM dictionary_tag WHERE name IN (:tagNames)")
    suspend fun getTagsByName(tagNames: List<String>): List<DictionaryTag>

    @Query("DELETE FROM dictionary_tag WHERE dictionaryId = :dictionaryId")
    suspend fun deleteTagsForDictionary(dictionaryId: Int)

    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun getCount(): Int

    @Query("DELETE FROM dictionary")
    suspend fun clearAll()
}
