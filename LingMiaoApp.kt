package com.lingmiao

import android.app.Application
import com.lingmiao.core.config.ConfigStore
import com.lingmiao.core.log.LingMiaoLogger
import com.lingmiao.data.db.LingMiaoDatabase

/**
 * 灵喵 LingMiao Application
 * 
 * 初始化:
 * - 日志系统
 * - 配置存储
 * - 数据库
 * - 崩溃捕获
 */
class LingMiaoApp : Application() {
    
    companion object {
        lateinit var instance: LingMiaoApp
            private set
        lateinit var config: ConfigStore
            private set
        lateinit var logger: LingMiaoLogger
            private set
        lateinit var database: LingMiaoDatabase
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化日志
        logger = LingMiaoLogger(this)
        logger.i("App", "灵喵 LingMiao v1.0.0 启动")
        
        // 初始化配置
        config = ConfigStore(this)
        config.loadDefaults()
        
        // 初始化数据库
        database = LingMiaoDatabase(this)
        
        // 崩溃捕获
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logger.e("Crash", "Uncaught exception in thread ${t.name}: ${e.message}")
            logger.logException(e)
            // 不阻止系统默认处理
            System.exit(1)
        }
        
        logger.i("App", "初始化完成 - Build: ${BuildConfig.VERSION_NAME}")
    }
    
    override fun onTerminate() {
        logger.i("App", "灵喵正在关闭...")
        database.close()
        super.onTerminate()
    }
}
