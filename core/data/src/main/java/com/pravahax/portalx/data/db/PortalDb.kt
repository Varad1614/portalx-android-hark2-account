package com.pravahax.portalx.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Last good response per cache key. [value] is sealed (AES-GCM), never plain JSON. */
@Entity(tableName = "cache_entry")
data class CacheEntry(@PrimaryKey val key: String, val value: String, val updatedAt: Long)

/**
 * A write made while offline, waiting to be sent by [com.pravahax.portalx.sync.OutboxWorker].
 * [payload] is sealed. [idempotencyKey] is generated once and sent on every attempt so the gateway can drop duplicates.
 */
@Entity(tableName = "outbox")
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fn: String,
    val payload: String,
    val idempotencyKey: String,
    val createdAt: Long,
    val attempts: Int = 0,
    /** [STATE_PENDING], [STATE_FAILED] (server refused it) or [STATE_UNCONFIRMED] (sent, but no answer came back). */
    val state: String = STATE_PENDING,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_FAILED = "failed"
        const val STATE_UNCONFIRMED = "unconfirmed"
    }
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entry WHERE `key` = :key") fun observe(key: String): Flow<CacheEntry?>
    @Query("SELECT * FROM cache_entry WHERE `key` = :key") suspend fun get(key: String): CacheEntry?
    @Upsert suspend fun put(e: CacheEntry)
    @Query("DELETE FROM cache_entry") suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(i: OutboxItem): Long
    @Query("SELECT * FROM outbox ORDER BY id") fun observeAll(): Flow<List<OutboxItem>>
    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY id") suspend fun pending(): List<OutboxItem>
    @Query("SELECT * FROM outbox WHERE id = :id") suspend fun get(id: Long): OutboxItem?
    @Update suspend fun update(i: OutboxItem)
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM outbox") suspend fun clear()
}

@Database(entities = [CacheEntry::class, OutboxItem::class], version = 1, exportSchema = true)
abstract class PortalDb : RoomDatabase() {
    abstract fun cache(): CacheDao
    abstract fun outbox(): OutboxDao

    companion object {
        /**
         * The on-disk database. Only opened when values can be sealed; without a Keystore the app keeps its cache
         * and outbox in memory ([MemoryCacheDao], [MemoryOutboxDao]), so nothing is ever written in clear.
         * [driver] is for tests (Robolectric has no native SQLite on every host).
         */
        fun open(context: Context, name: String, driver: androidx.sqlite.SQLiteDriver? = null, inMemory: Boolean = false): PortalDb =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, PortalDb::class.java)
            else Room.databaseBuilder(context, PortalDb::class.java, "portalx-$name.db"))
                .apply { driver?.let { setDriver(it) } }
                .fallbackToDestructiveMigration(dropAllTables = true) // a cache + unsent writes; never block launch on a migration
                .build()
    }
}

/** Memory-only cache for devices without a usable Keystore (same contract as the Room DAO). */
class MemoryCacheDao : CacheDao {
    private val rows = kotlinx.coroutines.flow.MutableStateFlow<Map<String, CacheEntry>>(emptyMap())
    override fun observe(key: String): Flow<CacheEntry?> = rows.map { it[key] }.distinctUntilChanged()
    override suspend fun get(key: String) = rows.value[key]
    override suspend fun put(e: CacheEntry) = rows.update { it + (e.key to e) }
    override suspend fun clear() { rows.value = emptyMap() }
}

/** Memory-only outbox for devices without a usable Keystore (same contract as the Room DAO). */
class MemoryOutboxDao : OutboxDao {
    private val rows = kotlinx.coroutines.flow.MutableStateFlow<List<OutboxItem>>(emptyList())
    private val next = java.util.concurrent.atomic.AtomicLong(1)
    override suspend fun insert(i: OutboxItem): Long { val id = next.getAndIncrement(); rows.update { it + i.copy(id = id) }; return id }
    override fun observeAll(): Flow<List<OutboxItem>> = rows
    override suspend fun pending() = rows.value.filter { it.state == OutboxItem.STATE_PENDING }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun update(i: OutboxItem) = rows.update { l -> l.map { if (it.id == i.id) i else it } }
    override suspend fun delete(id: Long) = rows.update { l -> l.filterNot { it.id == id } }
    override suspend fun clear() { rows.value = emptyList() }
}
