package com.lingmiao.service

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.*

/**
 * 许可证管理
 * 
 * 支持:
 * - 离线激活码验证
 * - 试用期限管理
 * - 设备绑定
 */
object LicenseManager {
    
    private const val PREF_NAME = "lingmiao_license"
    private const val TRIAL_DAYS = 7
    
    // ========== 激活码验证 ==========
    
    /**
     * 验证激活码
     * 
     * 格式: XXXX-XXXX-XXXX-XXXX (16位字母数字)
     * 算法: SHA-256(deviceId + salt) 前缀匹配
     */
    fun verify(context: Context, code: String): Boolean {
        val cleanCode = code.replace("-", "").uppercase()
        if (cleanCode.length != 16) return false
        if (!cleanCode.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        
        // 设备指纹
        val deviceId = getDeviceFingerprint(context)
        
        // 尝试验证
        // 方式1: 正式版密钥
        if (verifyOfficial(cleanCode, deviceId)) return true
        
        // 方式2: 万能密钥 (开发用)
        if (verifyMaster(cleanCode)) return true
        
        // 方式3: 离线密钥
        if (verifyOffline(cleanCode)) return true
        
        return false
    }
    
    private fun verifyOfficial(code: String, deviceId: String): Boolean {
        val salt = "lingmiao_2026"
        val hash = sha256("$deviceId$salt$code")
        // 激活码的前8位对应hash前8位（hex）
        val expected = hash.substring(0, 8).uppercase()
        return code.substring(0, 8) == expected
    }
    
    private fun verifyMaster(code: String): Boolean {
        // 开发用万能码 (硬编码hash)
        val masterHashes = listOf(
            "LM2026X8K3M9P4Q7",  // master-1
            "DEVMODE8X2K5L7N3",  // dev-1
            "TESTKEY1A2B3C4D5",  // test-1
        )
        return code in masterHashes
    }
    
    private fun verifyOffline(code: String): Boolean {
        // 离线激活码: 特定前缀 + 日期校验
        // 格式: LM + YYYYMMDD + XXXX
        val prefix = code.substring(0, 2)
        if (prefix != "LM") return false
        
        val dateStr = code.substring(2, 10)
        return try {
            val cal = Calendar.getInstance()
            val year = dateStr.substring(0, 4).toInt()
            val month = dateStr.substring(4, 6).toInt()
            val day = dateStr.substring(6, 8).toInt()
            
            // 检查日期合理性
            year in 2026..2030 && month in 1..12 && day in 1..31
        } catch (_: Exception) {
            false
        }
    }
    
    // ========== 设备指纹 ==========
    
    private fun getDeviceFingerprint(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // 已有指纹则复用
        var fingerprint = prefs.getString("device_fp", null)
        if (fingerprint != null) return fingerprint
        
        // 生成新指纹
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val buildInfo = "${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}-${android.os.Build.SERIAL}"
        
        fingerprint = sha256("$androidId|$buildInfo|lingmiao").substring(0, 16)
        
        prefs.edit().putString("device_fp", fingerprint).apply()
        return fingerprint
    }
    
    // ========== 试用管理 ==========
    
    fun startTrial(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong("trial_start", 0) == 0L) {
            prefs.edit()
                .putLong("trial_start", System.currentTimeMillis())
                .putInt("trial_days", TRIAL_DAYS)
                .putBoolean("is_trial", true)
                .apply()
        }
    }
    
    fun getTrialRemainingDays(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val start = prefs.getLong("trial_start", 0)
        if (start == 0L) return TRIAL_DAYS
        
        val elapsed = (System.currentTimeMillis() - start) / (24 * 3600 * 1000)
        val remaining = TRIAL_DAYS - elapsed.toInt()
        return max(0, remaining)
    }
    
    fun isTrialExpired(context: Context): Boolean {
        return getTrialRemainingDays(context) <= 0
    }
    
    // ========== 状态查询 ==========
    
    fun isActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences("lingmiao", Context.MODE_PRIVATE)
        return prefs.getBoolean("activated", false)
    }
    
    fun getLicenseInfo(context: Context): String {
        val prefs = context.getSharedPreferences("lingmiao", Context.MODE_PRIVATE)
        val license = prefs.getString("license", "") ?: ""
        
        if (license.isNotEmpty()) {
            return "已激活\n激活码: ${license.substring(0, 4)}****${license.takeLast(4)}"
        }
        
        val trialDays = getTrialRemainingDays(context)
        return if (trialDays > 0) {
            "试用版\n剩余天数: $trialDays 天"
        } else {
            "已过期\n请激活后继续使用"
        }
    }
    
    // ========== 工具 ==========
    
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
