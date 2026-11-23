package com.lifehub.plugin.memo.apk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lifehub.app.plugins.MemoPlugin
import com.lifehub.pluginapi.PluginApkProtocol
import com.lifehub.pluginapi.PluginDataSnapshot
import com.lifehub.pluginapi.PluginRecord
import com.lifehub.pluginapi.PluginSnapshotCollection
import com.lifehub.pluginapi.PluginSnapshotRecord
import com.lifehub.pluginapi.PluginStorage
import com.lifehub.pluginapi.PluginStorageLimits
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * APK 插件自己的 SQLite 存储。插件拥有独立数据库，宿主不能直接读写。
 * 备份通过 [exportSnapshot] / [importSnapshot] 走 provider。
 */
class MemoApkStorage(context: Context) : PluginStorage {

    private val helper = Helper(context.applicationContext)
    private val json = PluginApkProtocol.json

    override fun insert(collection: String, payload: String, id: String?): PluginRecord {
        requireCollection(collection)
        requirePayload(payload)
        val recordId = id ?: UUID.randomUUID().toString()
        requireId(recordId)
        if (count(collection) >= PluginStorageLimits.MAX_RECORDS_PER_COLLECTION) {
            throw IllegalStateException(
                "集合 $collection 的记录数达到上限 ${PluginStorageLimits.MAX_RECORDS_PER_COLLECTION}",
            )
        }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_COLLECTION, collection)
            put(COL_ID, recordId)
            put(COL_PAYLOAD, payload)
            put(COL_CREATED_AT, now)
            put(COL_UPDATED_AT, now)
        }
        try {
            helper.writableDatabase.insertOrThrow(TABLE_RECORDS, null, values)
        } catch (error: SQLiteConstraintException) {
            throw IllegalArgumentException("记录已存在：$collection/$recordId", error)
        }
        return PluginRecord(collection, recordId, payload, now, now)
    }

    override fun update(collection: String, id: String, payload: String): Boolean {
        requireCollection(collection)
        requireId(id)
        requirePayload(payload)
        val values = ContentValues().apply {
            put(COL_PAYLOAD, payload)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        return helper.writableDatabase.update(
            TABLE_RECORDS,
            values,
            "$COL_COLLECTION = ? AND $COL_ID = ?",
            arrayOf(collection, id),
        ) > 0
    }

    override fun delete(collection: String, id: String): Boolean {
        requireCollection(collection)
        requireId(id)
        return helper.writableDatabase.delete(
            TABLE_RECORDS,
            "$COL_COLLECTION = ? AND $COL_ID = ?",
            arrayOf(collection, id),
        ) > 0
    }

    override fun get(collection: String, id: String): PluginRecord? {
        requireCollection(collection)
        requireId(id)
        helper.readableDatabase.query(
            TABLE_RECORDS,
            null,
            "$COL_COLLECTION = ? AND $COL_ID = ?",
            arrayOf(collection, id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.readRecord() else null
        }
    }

    override fun list(collection: String, limit: Int, offset: Int): List<PluginRecord> {
        requireCollection(collection)
        requirePage(limit, offset)
        val records = mutableListOf<PluginRecord>()
        helper.readableDatabase.query(
            TABLE_RECORDS,
            null,
            "$COL_COLLECTION = ?",
            arrayOf(collection),
            null,
            null,
            "$COL_UPDATED_AT DESC, $COL_ID ASC",
            "$limit OFFSET $offset",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records += cursor.readRecord()
            }
        }
        return records
    }

    override fun count(collection: String): Int {
        requireCollection(collection)
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_RECORDS WHERE $COL_COLLECTION = ?",
            arrayOf(collection),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun collections(): List<String> {
        val values = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT $COL_COLLECTION FROM $TABLE_RECORDS ORDER BY $COL_COLLECTION",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                values += cursor.getString(0)
            }
        }
        return values
    }

    override fun clear() {
        helper.writableDatabase.delete(TABLE_RECORDS, null, null)
    }

    fun readAll(collection: String): List<PluginRecord> {
        val records = mutableListOf<PluginRecord>()
        var offset = 0
        while (true) {
            val batch = list(collection, PluginStorageLimits.MAX_PAGE_SIZE, offset)
            if (batch.isEmpty()) break
            records += batch
            offset += batch.size
            if (records.size >= PluginStorageLimits.MAX_RECORDS_PER_COLLECTION) break
        }
        return records
    }

    fun exportSnapshot(): String {
        val snapshot = PluginDataSnapshot(
            pluginId = MemoPlugin.PLUGIN_ID,
            dataSchemaVersion = DATA_SCHEMA_VERSION,
            collections = collections().map { collection ->
                PluginSnapshotCollection(
                    collection = collection,
                    records = readAll(collection).map { record ->
                        PluginSnapshotRecord(id = record.id, payload = record.payload)
                    },
                )
            },
        )
        return json.encodeToString(snapshot)
    }

    fun importSnapshot(raw: String): Int {
        require(raw.toByteArray(Charsets.UTF_8).size <= PluginApkProtocol.MAX_PAYLOAD_BYTES) {
            "备份数据超过 Binder 负载上限"
        }
        val snapshot = json.decodeFromString<PluginDataSnapshot>(raw)
        require(snapshot.pluginId == MemoPlugin.PLUGIN_ID) {
            "备份插件 ID 不匹配：${snapshot.pluginId}"
        }
        require(snapshot.dataSchemaVersion == DATA_SCHEMA_VERSION) {
            "备份数据版本不兼容：v${snapshot.dataSchemaVersion}"
        }

        var recordCount = 0
        snapshot.collections.forEach { collection ->
            requireCollection(collection.collection)
            require(collection.records.size <= PluginStorageLimits.MAX_RECORDS_PER_COLLECTION) {
                "集合 ${collection.collection} 记录数超过上限"
            }
            val ids = mutableSetOf<String>()
            collection.records.forEach { record ->
                requireId(record.id)
                requirePayload(record.payload)
                require(ids.add(record.id)) { "集合 ${collection.collection} 存在重复记录" }
            }
            recordCount += collection.records.size
        }

        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_RECORDS, null, null)
            val now = System.currentTimeMillis()
            snapshot.collections.forEach { collection ->
                collection.records.forEach { record ->
                    val values = ContentValues().apply {
                        put(COL_COLLECTION, collection.collection)
                        put(COL_ID, record.id)
                        put(COL_PAYLOAD, record.payload)
                        put(COL_CREATED_AT, now)
                        put(COL_UPDATED_AT, now)
                    }
                    database.insertOrThrow(TABLE_RECORDS, null, values)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return recordCount
    }

    private fun requireCollection(collection: String) {
        require(COLLECTION_PATTERN.matches(collection)) {
            "集合名不合法：$collection"
        }
    }

    private fun requireId(id: String) {
        require(ID_PATTERN.matches(id)) { "记录 ID 不合法：$id" }
    }

    private fun requirePayload(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        require(bytes <= PluginStorageLimits.MAX_RECORD_BYTES) {
            "记录内容 $bytes 字节超过上限 ${PluginStorageLimits.MAX_RECORD_BYTES} 字节"
        }
    }

    private fun requirePage(limit: Int, offset: Int) {
        require(offset >= 0) { "offset 不能为负数" }
        require(limit in 1..PluginStorageLimits.MAX_PAGE_SIZE) {
            "limit 必须在 1..${PluginStorageLimits.MAX_PAGE_SIZE} 之间"
        }
    }

    private fun android.database.Cursor.readRecord(): PluginRecord = PluginRecord(
        collection = getString(getColumnIndexOrThrow(COL_COLLECTION)),
        id = getString(getColumnIndexOrThrow(COL_ID)),
        payload = getString(getColumnIndexOrThrow(COL_PAYLOAD)),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
    )

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_RECORDS (
                    $COL_COLLECTION TEXT NOT NULL,
                    $COL_ID TEXT NOT NULL,
                    $COL_PAYLOAD TEXT NOT NULL,
                    $COL_CREATED_AT INTEGER NOT NULL,
                    $COL_UPDATED_AT INTEGER NOT NULL,
                    PRIMARY KEY ($COL_COLLECTION, $COL_ID)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX idx_records_collection_updated " +
                    "ON $TABLE_RECORDS ($COL_COLLECTION, $COL_UPDATED_AT DESC)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val DATA_SCHEMA_VERSION: Int = 1

        private const val DATABASE_NAME = "lifehub_memo_plugin.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RECORDS = "records"
        private const val COL_COLLECTION = "collection"
        private const val COL_ID = "id"
        private const val COL_PAYLOAD = "payload"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"

        private val COLLECTION_PATTERN = Regex(
            "^[a-z][a-zA-Z0-9_]{0,${PluginStorageLimits.MAX_COLLECTION_NAME_LENGTH - 1}}$",
        )
        private val ID_PATTERN = Regex(
            "^[a-zA-Z0-9_-]{1,${PluginStorageLimits.MAX_RECORD_ID_LENGTH}}$",
        )
    }
}
