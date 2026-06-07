package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items ORDER BY isPinned DESC, timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ClipboardItem): Long

    @Query("UPDATE clipboard_items SET isPinned = :isPinned WHERE id = :id")
    suspend fun updatePinnedState(id: Int, isPinned: Boolean)

    @Delete
    suspend fun deleteItem(item: ClipboardItem)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteItemById(id: Int)

    @Query("DELETE FROM clipboard_items WHERE isPinned = 0 AND id NOT IN (SELECT id FROM clipboard_items WHERE isPinned = 0 ORDER BY timestamp DESC LIMIT 50)")
    suspend fun pruneOldUnpinnedHistory()
}
