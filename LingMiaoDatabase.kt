package com.lingmiao.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Date

/**
 * 灵喵数据库
 * 
 * 存储:
 * - 击球记录 (成功/失败/路线类型)
 * - 校准历史
 * - 性能统计
 */
class LingMiaoDatabase(context: Context) :
    SQLiteOpenHelper(context, "lingmiao.db", null, 3) {
    
    companion object {
        const val TABLE_SHOTS = "shots"
        const val TABLE_CALIB = "calibration_history"
        const val TABLE_STATS = "performance_stats"
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // 击球记录
        db.execSQL("""
            CREATE TABLE $TABLE_SHOTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                aim_mode TEXT NOT NULL,
                route_type TEXT NOT NULL,
                bounces INTEGER DEFAULT 0,
                cut_angle REAL,
                success_rate REAL,
                difficulty REAL,
                actual_result TEXT,
                game_name TEXT,
                notes TEXT
            )
        """)
        
        // 校准历史
        db.execSQL("""
            CREATE TABLE $TABLE_CALIB (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                confidence REAL,
                table_type TEXT,
                corners_json TEXT,
                homography_json TEXT
            )
        """)
        
        // 性能统计
        db.execSQL("""
            CREATE TABLE $TABLE_STATS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                total_shots INTEGER DEFAULT 0,
                successful INTEGER DEFAULT 0,
                bank_shots INTEGER DEFAULT 0,
                direct_shots INTEGER DEFAULT 0,
                combo_shots INTEGER DEFAULT 0,
                avg_success_rate REAL,
                avg_cut_angle REAL,
                avg_fps REAL
            )
        """)
        
        // 索引
        db.execSQL("CREATE INDEX idx_shots_time ON $TABLE_SHOTS(timestamp)")
        db.execSQL("CREATE INDEX idx_stats_date ON $TABLE_STATS(date)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE $TABLE_SHOTS ADD COLUMN game_name TEXT")
        }
        if (oldV < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_STATS (id INTEGER PRIMARY KEY)")
        }
    }
    
    // ========== 击球记录 ==========
    
    data class ShotRecord(
        val id: Long = -1,
        val timestamp: Long,
        val aimMode: String,
        val routeType: String,  // "direct", "bank", "combo"
        val bounces: Int,
        val cutAngle: Double,
        val successRate: Double,
        val difficulty: Double,
        val actualResult: String,  // "potted", "missed", "partial"
        val gameName: String = "",
        val notes: String = ""
    )
    
    fun insertShot(record: ShotRecord): Long {
        val values = ContentValues().apply {
            put("timestamp", record.timestamp)
            put("aim_mode", record.aimMode)
            put("route_type", record.routeType)
            put("bounces", record.bounces)
            put("cut_angle", record.cutAngle)
            put("success_rate", record.successRate)
            put("difficulty", record.difficulty)
            put("actual_result", record.actualResult)
            put("game_name", record.gameName)
            put("notes", record.notes)
        }
        return writableDatabase.insert(TABLE_SHOTS, null, values)
    }
    
    fun getRecentShots(limit: Int = 50): List<ShotRecord> {
        val cursor = readableDatabase.query(
            TABLE_SHOTS, null, null, null, null, null,
            "timestamp DESC", limit.toString()
        )
        val results = mutableListOf<ShotRecord>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(ShotRecord(
                    id = it.getLong(0),
                    timestamp = it.getLong(1),
                    aimMode = it.getString(2) ?: "",
                    routeType = it.getString(3) ?: "",
                    bounces = it.getInt(4),
                    cutAngle = it.getDouble(5),
                    successRate = it.getDouble(6),
                    difficulty = it.getDouble(7),
                    actualResult = it.getString(8) ?: "",
                    gameName = it.getString(9) ?: "",
                    notes = it.getString(10) ?: ""
                ))
            }
        }
        return results
    }
    
    fun getStats(): ShotStats {
        val cursor = readableDatabase.rawQuery("""
            SELECT 
                COUNT(*) as total,
                SUM(CASE WHEN actual_result='potted' THEN 1 ELSE 0 END) as success,
                SUM(CASE WHEN route_type='bank' THEN 1 ELSE 0 END) as banks,
                SUM(CASE WHEN route_type='direct' THEN 1 ELSE 0 END) as directs,
                SUM(CASE WHEN route_type='combo' THEN 1 ELSE 0 END) as combos,
                AVG(success_rate) as avg_sr,
                AVG(cut_angle) as avg_ca
            FROM $TABLE_SHOTS
        """, null)
        
        cursor.use {
            if (it.moveToFirst()) {
                return ShotStats(
                    total = it.getInt(0),
                    successful = it.getInt(1),
                    bankShots = it.getInt(2),
                    directShots = it.getInt(3),
                    comboShots = it.getInt(4),
                    avgSuccessRate = it.getDouble(5),
                    avgCutAngle = it.getDouble(6)
                )
            }
        }
        return ShotStats()
    }
    
    data class ShotStats(
        val total: Int = 0,
        val successful: Int = 0,
        val bankShots: Int = 0,
        val directShots: Int = 0,
        val comboShots: Int = 0,
        val avgSuccessRate: Double = 0.0,
        val avgCutAngle: Double = 0.0
    ) {
        val successRate: Double get() = if (total > 0) successful.toDouble() / total else 0.0
    }
    
    // ========== 校准历史 ==========
    
    fun insertCalibration(confidence: Double, tableType: String, corners: String, homography: String) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("confidence", confidence)
            put("table_type", tableType)
            put("corners_json", corners)
            put("homography_json", homography)
        }
        writableDatabase.insert(TABLE_CALIB, null, values)
    }
    
    // ========== 性能统计 ==========
    
    fun recordDailyStats(avgFps: Float) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
        val values = ContentValues().apply {
            put("date", today)
            put("avg_fps", avgFps)
        }
        writableDatabase.insertWithOnConflict(TABLE_STATS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    
    // ========== 清理 ==========
    
    fun clearAll() {
        writableDatabase.delete(TABLE_SHOTS, null, null)
        writableDatabase.delete(TABLE_CALIB, null, null)
        writableDatabase.delete(TABLE_STATS, null, null)
    }
    
    fun getDatabaseSize(): Long {
        return context.getDatabasePath("lingmiao.db").length()
    }
}
