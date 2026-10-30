package com.qingyu.imemodule.database

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.database.entry.Phrase
import com.qingyu.imemodule.database.entry.SideSymbol
import com.qingyu.imemodule.database.entry.SkbFun
import com.qingyu.imemodule.database.entry.UsedSymbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Callable

/**
 * App-user-local database access. Keyboard rendering reads detached snapshots;
 * writes and database initialization run in one ordered background queue.
 * Pending writes survive a keyboard view being hidden or destroyed.
 */
object ImeDataRepository {
    private data class Snapshot(
        val clipboard: List<Clipboard> = emptyList(),
        val phrases: List<Phrase> = emptyList(),
        val pinyinSymbols: List<SideSymbol> = emptyList(),
        val numberSymbols: List<SideSymbol> = emptyList(),
        val menus: List<SkbFun> = emptyList(),
        val barMenus: List<SkbFun> = emptyList(),
        val symbols: List<UsedSymbol> = emptyList(),
        val emoji: List<UsedSymbol> = emptyList(),
    )

    enum class LoadState { Loading, Ready, Failed }

    @Volatile private var snapshot = Snapshot()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val state = MutableStateFlow(LoadState.Loading)
    val loadState = state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val writes = Channel<Operation>(64)
    private var started = false
    private var restoreNeedsRestart = false

    private sealed interface Operation
    private data class Mutation(val write: DataBaseKT.() -> Unit, val complete: () -> Unit, val failed: () -> Unit) : Operation
    private class DataFiles(val work: () -> Unit) : Operation

    @Synchronized
    fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            reload(initialize = true)
            for (operation in writes) {
                if (operation is DataFiles) {
                    operation.work()
                    continue
                }
                val mutation = operation as Mutation
                if (restoreNeedsRestart) {
                    main.post(mutation.failed)
                    continue
                }
                try {
                    val database = DataBaseKT.instance
                    database.runInTransaction { mutation.write(database) }
                    if (reload(initialize = false)) main.post(mutation.complete) else main.post(mutation.failed)
                } catch (_: Exception) {
                    // SQLite failures may contain user text. Never log the exception or SQL.
                    reportFailure(R.string.data_save_failed)
                    main.post(mutation.failed)
                }
            }
        }
    }

    fun mutate(onComplete: () -> Unit = {}, onFailure: () -> Unit = {}, write: DataBaseKT.() -> Unit) {
        start()
        if (writes.trySend(Mutation(write, onComplete, onFailure)).isFailure) {
            reportFailure(R.string.data_save_failed)
            main.post(onFailure)
        }
    }

    fun retry() {
        state.value = LoadState.Loading
        mutate { initializeDefaults() }
    }

    /** Ordered with all app writes. Import holds further mutations until the process restarts. */
    suspend fun <T> withDataFiles(replacing: Boolean, work: () -> T): T {
        start()
        val result = CompletableDeferred<T>()
        writes.send(DataFiles {
            try {
                check(!restoreNeedsRestart) { "恢复后需要先重启输入法" }
                if (replacing) DataBaseKT.closeForRestore()
                else DataBaseKT.instance.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "无法创建一致的数据库备份" }
                }
                val value = work()
                if (replacing) restoreNeedsRestart = true
                result.complete(value)
            } catch (error: Exception) {
                if (replacing) reload(initialize = false)
                result.completeExceptionally(error)
            }
        })
        return result.await()
    }

    private fun reload(initialize: Boolean): Boolean =
        try {
            val database = DataBaseKT.instance
            if (initialize) database.initializeDefaults()
            snapshot = database.runInTransaction(Callable {
                Snapshot(
                    database.clipboardDao().getAll(), database.phraseDao().getAll(),
                    database.sideSymbolDao().getAllSideSymbolPinyin(), database.sideSymbolDao().getAllSideSymbolNumber(),
                    database.skbFunDao().getAllMenu(), database.skbFunDao().getALlBarMenu(),
                    database.usedSymbolDao().getAllUsedSymbol(), database.usedSymbolDao().getAllSymbolEmoji(),
                )
            })
            state.value = LoadState.Ready
            revision.value += 1
            true
        } catch (_: Exception) {
            state.value = LoadState.Failed
            reportFailure(R.string.data_load_failed)
            false
        }

    private fun reportFailure(message: Int) {
        main.post { Toast.makeText(Launcher.instance.context, message, Toast.LENGTH_LONG).show() }
    }

    // Entities contain editable fields. Never share the cached instances with a view.
    fun clipboard() = snapshot.clipboard.map { it.copy() }
    fun phrases() = snapshot.phrases.map { it.copy() }
    fun phrase(content: String) = snapshot.phrases.firstOrNull { it.content == content }?.copy()
    fun phrasesForCode(code: String) = snapshot.phrases
        .filter { it.qwerty == code || it.t9 == code || it.lx17 == code }.map { it.copy() }

    /** 候选装配热路径只读正文，不需要可编辑实体副本。 */
    fun phraseContentsForCode(code: String) = snapshot.phrases
        .filter { it.qwerty == code || it.t9 == code || it.lx17 == code }.map { it.content }
    fun sideSymbols(type: String = "pinyin") =
        (if (type == "number") snapshot.numberSymbols else snapshot.pinyinSymbols).map { it.copy() }
    fun menus() = snapshot.menus.map { it.copy() }
    fun barMenus() = snapshot.barMenus.map { it.copy() }
    fun hasBarMenu(name: String) = snapshot.barMenus.any { it.name == name }
    fun recentSymbols(emoji: Boolean = false) =
        (if (emoji) snapshot.emoji else snapshot.symbols).map { it.copy() }
}
