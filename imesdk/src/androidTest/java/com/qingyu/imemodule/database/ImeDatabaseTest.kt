package com.qingyu.imemodule.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.database.entry.Phrase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Tests use a separate synthetic database and never read the user's IME database. */
class ImeDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var name: String
    private var database: DataBaseKT? = null

    @Before fun prepare() { name = "ime-regression-${UUID.randomUUID()}" }

    @After fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun open() = DataBaseKT.create(context, name).also { database = it }

    @Test fun historyLimitKeepsPinsAndReplacesDuplicateHistory() {
        val dao = open().clipboardDao()
        dao.insert(Clipboard("synthetic-pinned", isKeep = 1, time = 1L))
        dao.recordWithLimit(Clipboard("synthetic-old", time = 2L), 1)
        dao.recordWithLimit(Clipboard("synthetic-new", time = 3L), 1)
        assertEquals(1, dao.getByContent("synthetic-pinned")?.isKeep)
        assertNull(dao.getByContent("synthetic-old"))
        assertNotNull(dao.getByContent("synthetic-new"))
        dao.recordWithLimit(Clipboard("synthetic-pinned", time = 4L), 1)
        assertEquals(1, dao.getByContent("synthetic-pinned")?.isKeep)
        assertEquals(1, dao.getHistoryCount())
    }

    @Test fun clearHistoryKeepsPins() {
        val dao = open().clipboardDao()
        dao.insertAll(listOf(Clipboard("synthetic-pin", 1), Clipboard("synthetic-history")))
        dao.deleteAllExceptKeep()
        assertEquals(listOf("synthetic-pin"), dao.getAll().map { it.content })
    }

    @Test fun moveToPhrasesRemovesClipboardAndKeepsExistingShortcuts() {
        val db = open()
        val clipboard = db.clipboardDao()
        clipboard.insert(Clipboard("synthetic-new-phrase"))
        clipboard.moveToPhrases("synthetic-new-phrase")
        assertNull(clipboard.getByContent("synthetic-new-phrase"))
        assertEquals("synthetic-new-phrase", db.phraseDao().getAll().single().content)
        val existing = Phrase("synthetic-existing-phrase", 1, "1234", "test", "test", 7L)
        db.phraseDao().insert(existing)
        clipboard.insert(Clipboard(existing.content))
        clipboard.moveToPhrases(existing.content)
        assertNull(clipboard.getByContent(existing.content))
        assertEquals(existing, db.phraseDao().queryByContent(existing.content))
    }

    @Test fun failedMoveKeepsClipboardAndRollsBackNewPhrase() {
        val db = open()
        db.clipboardDao().insert(Clipboard("synthetic-move-failure"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_clipboard_move BEFORE DELETE ON clipboard BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        var failed = false
        try { db.clipboardDao().moveToPhrases("synthetic-move-failure") }
        catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue(failed)
        assertNotNull(db.clipboardDao().getByContent("synthetic-move-failure"))
        assertTrue(db.phraseDao().getAll().isEmpty())
    }

    @Test fun upgradeFromVersionOnePreservesStoredData() {
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE clipboard (content TEXT NOT NULL PRIMARY KEY, isKeep INTEGER NOT NULL, time INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE side_symbol (symbolKey TEXT NOT NULL PRIMARY KEY, symbolValue TEXT NOT NULL, type TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE usedSymbol (symbol TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, time INTEGER NOT NULL)")
            legacy.execSQL("INSERT INTO clipboard VALUES ('synthetic-legacy-pin', 1, 1)")
            legacy.execSQL("INSERT INTO side_symbol VALUES ('synthetic-key', 'synthetic-value', 'pinyin')")
            legacy.execSQL("INSERT INTO usedSymbol VALUES ('synthetic-symbol', 'emoji', 1)")
            legacy.version = 1
        }
        val upgraded = open()
        assertEquals(1, upgraded.clipboardDao().getByContent("synthetic-legacy-pin")?.isKeep)
        assertEquals("synthetic-value", upgraded.sideSymbolDao().getByKey("synthetic-key")?.symbolValue)
        assertEquals("synthetic-symbol", upgraded.usedSymbolDao().getAllSymbolEmoji().single().symbol)
        assertEquals(6, upgraded.openHelper.readableDatabase.version)
        assertEquals(listOf("Emojicon", "SwitchKeyboard", "TextEdit", "ClipBoard"),
            upgraded.skbFunDao().getALlBarMenu().map { it.name })
    }

    @Test fun databaseQueriesOnMainThreadAreRejected() {
        val db = open()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try { db.clipboardDao().getAll() } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(failure.get() is IllegalStateException)
    }
}
