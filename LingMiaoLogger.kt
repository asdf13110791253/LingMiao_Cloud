package com.lingmiao.core.log

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 灵喵日志系统
 * 
 * - 分级日志 (V/D/I/W/E)
 * - 文件落盘（循环写入）
 * - 崩溃日志捕获
 * - 日志导出
 */
class LingMiaoLogger(private val context: Context) {
    
    enum class Level(val tag: String, val priority: Int) {
        VERBOSE("V", 0),
        DEBUG("D", 1),
        INFO("I", 2),
        WARN("W", 3),
        ERROR("E", 4)
    }
    
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val logDir: File
    private var currentFile: File? = null
    private var writer: FileWriter? = null
    private val maxFileSize = 2 * 1024 * 1024  // 2MB per file
    private val maxFiles = 5
    private val minLevel = Level.DEBUG  // 过滤低于此级别的日志
    
    init {
        logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) logDir.mkdirs()
        openNewFile()
    }
    
    // ========== 日志方法 ==========
    
    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)
    
    fun logException(e: Throwable) {
        val sb = StringBuilder()
        sb.append("Exception: ${e.javaClass.simpleName}: ${e.message}\n")
        e.stackTrace.forEach { sb.append("  at $it\n") }
        e.cause?.let { cause ->
            sb.append("Caused by: ${cause.javaClass.simpleName}: ${cause.message}\n")
            cause.stackTrace.forEach { sb.append("  at $it\n") }
        }
        log(Level.ERROR, "Crash", sb.toString())
    }
    
    // ========== 内部 ==========
    
    @Synchronized
    private fun log(level: Level, tag: String, msg: String) {
        if (level.priority < minLevel.priority) return
        
        val timestamp = dateFmt.format(Date())
        val line = "$timestamp ${level.tag}/$tag: $msg\n"
        
        // 控制台
        when (level) {
            Level.VERBOSE -> android.util.Log.v(tag, msg)
            Level.DEBUG -> android.util.Log.d(tag, msg)
            Level.INFO -> android.util.Log.i(tag, msg)
            Level.WARN -> android.util.Log.w(tag, msg)
            Level.ERROR -> android.util.Log.e(tag, msg)
        }
        
        // 文件
        try {
            writer?.write(line)
            writer?.flush()
            
            // 检查文件大小
            if (currentFile?.length() ?: 0 > maxFileSize) {
                rotateFile()
            }
        } catch (_: Exception) {
            // 写入失败不影响运行
        }
    }
    
    private fun openNewFile() {
        val filename = "lingmiao_${fileDateFmt.format(Date())}.log"
        currentFile = File(logDir, filename)
        writer = FileWriter(currentFile, true)  // append
        
        // 写入启动标记
        val banner = "\n===== LingMiao Log Start [${Date()}] =====\n"
        writer?.write(banner)
        writer?.flush()
    }
    
    private fun rotateFile() {
        writer?.close()
        writer = null
        
        // 清理旧文件
        val files = logDir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        while (files.size >= maxFiles) {
            files.firstOrNull()?.delete()
            files.drop(1)
        }
        
        openNewFile()
    }
    
    // ========== 导出 ==========
    
    fun exportLogs(): File? {
        // 将当前所有日志合并导出
        val exportFile = File(context.getExternalFilesDir(null), "lingmiao_log_export.txt")
        try {
            FileWriter(exportFile).use { out ->
                logDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    out.write("=== ${f.name} ===\n")
                    f.readLines().forEach { out.write("$it\n") }
                }
            }
            return exportFile
        } catch (_: Exception) {
            return null
        }
    }
    
    fun clearLogs() {
        writer?.close()
        writer = null
        logDir.listFiles()?.forEach { it.delete() }
        openNewFile()
    }
    
    fun getLogDir(): File = logDir
}
