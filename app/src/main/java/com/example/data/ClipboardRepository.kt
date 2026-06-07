package com.example.data

import kotlinx.coroutines.flow.Flow

class ClipboardRepository(private val clipboardDao: ClipboardDao) {
    val allItems: Flow<List<ClipboardItem>> = clipboardDao.getAllItems()

    suspend fun insert(text: String, isPinned: Boolean = false): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return -1
        val item = ClipboardItem(text = trimmed, isPinned = isPinned)
        val id = clipboardDao.insertItem(item)
        clipboardDao.pruneOldUnpinnedHistory()
        return id
    }

    suspend fun updatePinnedState(id: Int, isPinned: Boolean) {
        clipboardDao.updatePinnedState(id, isPinned)
    }

    suspend fun deleteById(id: Int) {
        clipboardDao.deleteItemById(id)
    }

    suspend fun delete(item: ClipboardItem) {
        clipboardDao.deleteItem(item)
    }
}
