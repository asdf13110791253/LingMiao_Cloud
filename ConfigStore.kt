package com.lingmiao.core.config

import android.content.Context
import android.content.SharedPreferences
import com.lingmiao.engine.AimEngine

/**
 * 配置存储 - 所有用户设置持久化管理
 */
class ConfigStore(private val context: Context) {
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("lingmiao_config", Context.MODE_PRIVATE)
    }
    
    // ========== 默认值 ==========
    fun loadDefaults() {
        if (!prefs.contains("initialized")) {
            prefs.edit().apply {
                putBoolean("initialized", true)
                putFloat("comp_ratio", 0.18f)
                putInt("max_bounces", 3)
                putFloat("line_width", 5.0f)
                putInt("line_color", 0xFF00FF66.toInt())
                putBoolean("show_ant", false)
                putBoolean("snap_nearest", true)
                putString("scheme", "PRECISE")
                putInt("aim_mode", 2)  // HYBRID
                putInt("cloth", 1)
                putInt("brightness_thresh", 232)
                putInt("roundness_thresh", 15)
                putInt("sensitivity", 15)
                putBoolean("debug_mode", false)
                putBoolean("show_fps", true)
                putString("language", "zh")
                putString("game", "auto")
                putInt("quality", 1)  // 0=省电 1=均衡 2=高质量
                apply()
            }
        }
    }
    
    // ========== 读取 ==========
    
    fun getAimConfig(): AimEngine.AimConfig {
        return AimEngine.AimConfig(
            compensationRatio = prefs.getFloat("comp_ratio", 0.18f).toDouble(),
            maxBounces = prefs.getInt("max_bounces", 3),
            lineWidth = prefs.getFloat("line_width", 5.0f),
            lineColor = prefs.getInt("line_color", 0xFF00FF66.toInt()),
            showAntLine = prefs.getBoolean("show_ant", false),
            snapNearestBall = prefs.getBoolean("snap_nearest", true),
            scheme = AimEngine.RecognitionScheme.valueOf(
                prefs.getString("scheme", "PRECISE") ?: "PRECISE"
            )
        )
    }
    
    fun getAimMode(): AimEngine.AimMode {
        return when (prefs.getInt("aim_mode", 2)) {
            0 -> AimEngine.AimMode.MIRROR_REFLECTION
            1 -> AimEngine.AimMode.ANGLE_COMPENSATION
            else -> AimEngine.AimMode.HYBRID
        }
    }
    
    fun getBrightnessThresh(): Int = prefs.getInt("brightness_thresh", 232)
    fun getRoundnessThresh(): Int = prefs.getInt("roundness_thresh", 15)
    fun getSensitivity(): Int = prefs.getInt("sensitivity", 15)
    fun getClothIndex(): Int = prefs.getInt("cloth", 1)
    fun isDebugMode(): Boolean = prefs.getBoolean("debug_mode", false)
    fun showFps(): Boolean = prefs.getBoolean("show_fps", true)
    fun getLanguage(): String = prefs.getString("language", "zh") ?: "zh"
    fun getGame(): String = prefs.getString("game", "auto") ?: "auto"
    fun getQuality(): Int = prefs.getInt("quality", 1)
    
    // ========== 写入 ==========
    
    fun setCompensationRatio(v: Double) = prefs.edit().putFloat("comp_ratio", v.toFloat()).apply()
    fun setMaxBounces(v: Int) = prefs.edit().putInt("max_bounces", v).apply()
    fun setLineWidth(v: Float) = prefs.edit().putFloat("line_width", v).apply()
    fun setLineColor(v: Int) = prefs.edit().putInt("line_color", v).apply()
    fun setShowAntLine(v: Boolean) = prefs.edit().putBoolean("show_ant", v).apply()
    fun setSnapNearest(v: Boolean) = prefs.edit().putBoolean("snap_nearest", v).apply()
    fun setScheme(v: AimEngine.RecognitionScheme) = prefs.edit().putString("scheme", v.name).apply()
    fun setAimMode(v: AimEngine.AimMode) {
        val idx = when(v) {
            AimEngine.AimMode.MIRROR_REFLECTION -> 0
            AimEngine.AimMode.ANGLE_COMPENSATION -> 1
            AimEngine.AimMode.HYBRID -> 2
        }
        prefs.edit().putInt("aim_mode", idx).apply()
    }
    fun setBrightnessThresh(v: Int) = prefs.edit().putInt("brightness_thresh", v).apply()
    fun setRoundnessThresh(v: Int) = prefs.edit().putInt("roundness_thresh", v).apply()
    fun setSensitivity(v: Int) = prefs.edit().putInt("sensitivity", v).apply()
    fun setClothIndex(v: Int) = prefs.edit().putInt("cloth", v).apply()
    fun setDebugMode(v: Boolean) = prefs.edit().putBoolean("debug_mode", v).apply()
    fun setShowFps(v: Boolean) = prefs.edit().putBoolean("show_fps", v).apply()
    fun setLanguage(v: String) = prefs.edit().putString("language", v).apply()
    fun setGame(v: String) = prefs.edit().putString("game", v).apply()
    fun setQuality(v: Int) = prefs.edit().putInt("quality", v).apply()
    
    // ========== 导出/导入 ==========
    
    fun exportAll(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        prefs.all.forEach { (k, v) -> map[k] = v }
        return map
    }
    
    fun importAll(data: Map<String, Any>) {
        prefs.edit().apply {
            data.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Float -> putFloat(k, v)
                    is String -> putString(k, v)
                    is Long -> putLong(k, v)
                    else -> {}
                }
            }
            apply()
        }
    }
    
    fun reset() {
        prefs.edit().clear().apply()
        loadDefaults()
    }
}
