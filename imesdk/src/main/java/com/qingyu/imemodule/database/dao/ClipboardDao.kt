package com.qingyu.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.qingyu.imemodule.database.BaseDao
import com.qingyu.imemodule.database.entry.Clipboard

@Dao
interface ClipboardDao : BaseDao<Clipboard> {

    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Clipboard>

    @Query("delete from clipboard where content = :content")
    fun deleteByContent(content: String)

    @Query("delete from clipboard")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM clipboard")
    fun getCount(): Int

    @Query("DELETE FROM clipboard WHERE isKeep = 0 AND content IN ( SELECT content FROM clipboard WHERE isKeep = 0 ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(overflow: Int)

    @Query("SELECT * FROM clipboard WHERE content = :content")
    fun getByContent(content: String): Clipboard?

    @Query("SELECT COUNT(*) FROM clipboard WHERE isKeep = 0")
    fun getHistoryCount(): Int

    @Transaction
    fun recordWithLimit(item: Clipboard, limit: Int) {
        // Copying pinned text again retains its pin; trimming only affects history.
        insert(item.copy(isKeep = getByContent(item.content)?.isKeep ?: item.isKeep))
        deleteOldest((getHistoryCount() - limit.coerceAtLeast(0)).coerceAtLeast(0))
    }

    @Query("INSERT OR IGNORE INTO phrase (content, isKeep, t9, qwerty, lx17, time) SELECT content, 0, '', '', '', :time FROM clipboard WHERE content = :content")
    fun insertPhraseFromClipboard(content: String, time: Long)

    /** A duplicate phrase keeps its existing shortcut and pin; failed moves keep clipboard text. */
    @Transaction
    fun moveToPhrases(content: String) {
        insertPhraseFromClipboard(content, System.currentTimeMillis())
        deleteByContent(content)
    }

    @Query("DELETE FROM clipboard WHERE isKeep = 0")
    fun deleteAllExceptKeep()
}
