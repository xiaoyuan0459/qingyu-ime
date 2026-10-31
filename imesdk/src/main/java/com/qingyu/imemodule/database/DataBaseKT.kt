package com.qingyu.imemodule.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.database.dao.ClipboardDao
import com.qingyu.imemodule.database.dao.PhraseDao
import com.qingyu.imemodule.database.dao.SideSymbolDao
import com.qingyu.imemodule.database.dao.SkbFunDao
import com.qingyu.imemodule.database.dao.UsedSymbolDao
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.database.entry.Phrase
import com.qingyu.imemodule.database.entry.SideSymbol
import com.qingyu.imemodule.database.entry.SkbFun
import com.qingyu.imemodule.database.entry.UsedSymbol
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode

@Database(entities = [SideSymbol::class, Clipboard::class, UsedSymbol::class, Phrase::class, SkbFun::class], version = 8, exportSchema = true)
abstract class DataBaseKT : RoomDatabase() {
    abstract fun sideSymbolDao(): SideSymbolDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun usedSymbolDao(): UsedSymbolDao
    abstract fun phraseDao(): PhraseDao
    abstract fun skbFunDao(): SkbFunDao

    internal fun initializeDefaults() = runInTransaction { initPhrasesDb(this) }
    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS phrase (content TEXT PRIMARY KEY NOT NULL, isKeep INTEGER NOT NULL, t9 TEXT NOT NULL, qwerty TEXT NOT NULL, lx17 TEXT NOT NULL, time INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skbfun (name TEXT KEY NOT NULL, isKeep INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (name, isKeep))")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 0, 15)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 1, 0)")
            }
        }

        // 工具栏默认项对齐参考图：[表情][切换键盘][光标]（入库序为展示序倒序）
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM skbfun WHERE isKeep = 1")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 1, 0)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('SwitchKeyboard', 1, 0)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('Emojicon', 1, 0)")
            }
        }

        // 底部全面屏键盘条下线：剪贴板入口移进顶栏，默认项 [表情][切换键盘][光标][剪贴板]。
        // 顶栏改为顺序展示（不再 reverseLayout），故按展示序重写 position。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM skbfun WHERE isKeep = 1")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('Emojicon', 1, 0)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('SwitchKeyboard', 1, 1)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('TextEdit', 1, 2)")
                db.execSQL("INSERT INTO skbfun (name, isKeep, position) VALUES ('ClipBoard', 1, 3)")
            }
        }

        // 工具箱新增「键盘手写」直达：老库把 10 号之后的菜单项顺移一位，再把新项插到繁体输入后面。
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE skbfun SET position = position + 1 WHERE isKeep = 0 AND position >= 10")
                db.execSQL("INSERT OR IGNORE INTO skbfun (name, isKeep, position) VALUES ('PinyinHandWriting', 0, 10)")
            }
        }

        // 工具箱新增「反馈参数」：老库把 14 号之后顺移一位，插在花漾字与定制工具栏之间。
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE skbfun SET position = position + 1 WHERE isKeep = 0 AND position >= 14")
                db.execSQL("INSERT OR IGNORE INTO skbfun (name, isKeep, position) VALUES ('FeedbackInfo', 0, 14)")
            }
        }

        private var currentDatabase: DataBaseKT? = null
        val instance: DataBaseKT
            @Synchronized get() = currentDatabase ?: create(Launcher.instance.context, "ime_db").also { currentDatabase = it }

        @Synchronized
        fun closeForRestore() {
            currentDatabase?.close()
            currentDatabase = null
        }

        internal fun create(context: Context, name: String): DataBaseKT =
            Room.databaseBuilder(context, DataBaseKT::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8)
            .addCallback(object :Callback(){
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    for (symbol in listOf("，", "。", "？", "！", "……", "：", "；", ".")) {
                        db.execSQL("INSERT INTO side_symbol (symbolKey, symbolValue, type) VALUES (?, ?, 'pinyin')",
                            arrayOf(symbol, symbol))
                    }
                    for (symbol in listOf("%", "/", "-", "+", "*", "#", "@")) {
                        db.execSQL("INSERT INTO side_symbol (symbolKey, symbolValue, type) VALUES (?, ?, 'number')",
                            arrayOf(symbol, symbol))
                    }
                }
            })
            .build()
        private fun initPhrasesDb(database: DataBaseKT) {  //初始化常用语数据
            if(database.phraseDao().getAll().isEmpty()) {
                val phrases = listOf(
                    Phrase(content = "我的电话是__，常联系。", t9 = "9334", qwerty = "wddh", lx17 = "wddh"),
                    Phrase(content = "抱歉，我现在不方便接电话，稍后联系。", t9 = "2799", qwerty = "bqwx", lx17 = "bqwx"),
                    Phrase(content = "我正在开会，有急事请发短信。", t9 = "9995", qwerty = "wzzk", lx17 = "wwwj"),
                    Phrase(content = "我很快就到，请稍微等一会儿。", t9 = "9455", qwerty = "whkj", lx17 = "whjj"),
                    Phrase(content = "麻烦放驿站，谢谢。", t9 = "6339", qwerty = "mffy", lx17 = "mffy"),
                )
                database.phraseDao().insertAll(phrases)
            }
            if(database.skbFunDao().getAllMenu().isEmpty()) {
                val skbFuns = listOf(
                    // 顶栏默认项按展示序入库：[表情][切换键盘][光标][剪贴板]
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 1, position = 0),
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 1, position = 1),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 1, position = 2),
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 1, position = 3),
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 0, position = 0),
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 0, position = 1),
                    SkbFun(name = SkbMenuMode.KeyboardHeight.name, isKeep = 0, position = 2),
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 0, position = 3),
                    SkbFun(name = SkbMenuMode.Phrases.name, isKeep = 0, position = 4),
                    SkbFun(name = SkbMenuMode.DarkTheme.name, isKeep = 0, position = 5),
                    SkbFun(name = SkbMenuMode.Feedback.name, isKeep = 0, position = 6),
                    SkbFun(name = SkbMenuMode.OneHanded.name, isKeep = 0, position = 7),
                    SkbFun(name = SkbMenuMode.NumberRow.name, isKeep = 0, position = 8),
                    SkbFun(name = SkbMenuMode.JianFan.name, isKeep = 0, position = 9),
                    // 「键盘手写」紧跟繁体输入，与参考输入法的格子顺序一致
                    SkbFun(name = SkbMenuMode.PinyinHandWriting.name, isKeep = 0, position = 10),
                    SkbFun(name = SkbMenuMode.Mnemonic.name, isKeep = 0, position = 11),
                    SkbFun(name = SkbMenuMode.FloatKeyboard.name, isKeep = 0, position = 12),
                    SkbFun(name = SkbMenuMode.FlowerTypeface.name, isKeep = 0, position = 13),
                    SkbFun(name = SkbMenuMode.FeedbackInfo.name, isKeep = 0, position = 14),
                    SkbFun(name = SkbMenuMode.Custom.name, isKeep = 0, position = 15),
                    SkbFun(name = SkbMenuMode.Settings.name, isKeep = 0, position = 16),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 0, position = 17),
                )
                database.skbFunDao().insertAll(skbFuns)
            }
        }
    }
}
