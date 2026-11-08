package rocks.pkb.plektra.clipboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database helper for clipboard history.
 */
class ClipboardDatabase internal constructor(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE ${ClipboardDao.TABLE} ADD COLUMN SENSITIVE TINYINT NOT NULL DEFAULT 0")
        }
    }

    companion object {
        private const val VERSION = 2
        private const val NAME = "plektra_clipboard.db"

        @Volatile
        private var instance: ClipboardDatabase? = null

        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: ClipboardDatabase(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
