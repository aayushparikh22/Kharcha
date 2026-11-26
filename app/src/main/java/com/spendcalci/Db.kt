package com.spendcalci

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

val zone: ZoneId = ZoneId.systemDefault()

@Entity(tableName = "txn", indices = [Index(value = ["hash"], unique = true)])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hash: String,
    val amount: Double,
    val merchant: String,
    val source: String,
    val account: String,
    val timestamp: Long,
    val category: String? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val split: Boolean = false,
    val splitMembers: String? = null,
) {
    fun ym(): YearMonth = YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zone))
    fun day(): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    /** Colour/filter bucket: "UPI", "CASH", "OTHER", "CARD" or "CARD:<last4>". */
    fun bucket(): String =
        if (source == "CARD" && account.isNotEmpty() && account.all { it.isDigit() }) "CARD:$account" else source
}

@Entity(tableName = "custom_category")
data class CustomCategory(@PrimaryKey val name: String, val color: String, val emoji: String)

@Entity(tableName = "card_alias")
data class CardAlias(@PrimaryKey val suffix: String, val name: String, val color: String)

@Entity(tableName = "app_setting")
data class AppSetting(@PrimaryKey val key: String, val value: String)

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: Txn): Long

    @Query("UPDATE txn SET category = :cat, note = :note WHERE id = :id")
    suspend fun categorize(id: Long, cat: String, note: String)

    @Query("UPDATE txn SET category = :cat, note = :note, split = 0, splitMembers = NULL WHERE id = :id")
    suspend fun categorizeNormal(id: Long, cat: String, note: String)

    @Query("UPDATE txn SET category = :cat, note = :note, split = 1, splitMembers = :members WHERE id = :id")
    suspend fun categorizeSplit(id: Long, cat: String, note: String, members: String)

    @Query("UPDATE txn SET splitMembers = :members WHERE id = :id")
    suspend fun updateMembers(id: Long, members: String)

    @Query("UPDATE txn SET category = :newName WHERE category = :oldName")
    suspend fun reassignCategory(oldName: String, newName: String)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun get(id: Long): Txn?

    @Query("SELECT * FROM txn ORDER BY timestamp DESC")
    fun all(): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE timestamp >= :from AND timestamp < :to")
    suspend fun betweenList(from: Long, to: Long): List<Txn>

    @Query("SELECT DISTINCT account FROM txn WHERE source = 'CARD' AND account GLOB '[0-9]*' ORDER BY account")
    fun cardAccounts(): Flow<List<String>>

    @Query("UPDATE txn SET timestamp = :ts WHERE hash = :hash")
    suspend fun setTimestamp(hash: String, ts: Long)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM custom_category ORDER BY rowid")
    fun categories(): Flow<List<CustomCategory>>

    @Query("SELECT * FROM custom_category ORDER BY rowid")
    suspend fun categoriesNow(): List<CustomCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCategory(c: CustomCategory)

    @Query("DELETE FROM custom_category WHERE name = :name")
    suspend fun deleteCategory(name: String)

    @Query("SELECT * FROM card_alias")
    fun cards(): Flow<List<CardAlias>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCard(c: CardAlias)

    @Query("SELECT * FROM app_setting")
    fun prefs(): Flow<List<AppSetting>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(s: AppSetting)
}

@Database(
    entities = [Txn::class, CustomCategory::class, CardAlias::class, AppSetting::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TxnDao
    abstract fun settings(): SettingsDao

    companion object {
        @Volatile private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "spend.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { inst = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN split INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE txn ADD COLUMN splitMembers TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_category (name TEXT NOT NULL PRIMARY KEY, color TEXT NOT NULL, emoji TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS card_alias (suffix TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            }
        }
    }
}
