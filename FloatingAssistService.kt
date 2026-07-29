package com.lingmiao.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.lingmiao.engine.AimEngine
import com.lingmiao.engine.AimRenderer
import com.lingmiao.engine.BallDetector

/**
 * 悬浮辅助服务 - 灵喵核心服务
 * 
 * 功能:
 * 1. 前台服务保活
 * 2. 悬浮窗渲染瞄准线
 * 3. 录屏捕获 → 球检测 → 路线计算 → 绘制
 * 4. 用户交互（拖动/缩放/设置面板）
 */
class FloatingAssistService : Service() {
    
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var renderView: AssistRenderView
    private lateinit var aimEngine: AimEngine
    private lateinit var ballDetector: BallDetector
    private lateinit var renderer: AimRenderer
    
    private var isRunning = false
    private var currentBalls: List<AimEngine.Ball> = emptyList()
    private var currentAims: List<AimEngine.AimLine> = emptyList()
    private var cueBall: AimEngine.Ball? = null
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "lingmiao_assist"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 初始化引擎
        val config = AimEngine.AimConfig(
            compensationRatio = 0.18,
            maxBounces = 3,
            lineWidth = 5.0f,
            lineColor = 0xFF00FF66.toInt(),
            showAntLine = false,
            snapNearestBall = true,
            scheme = AimEngine.RecognitionScheme.PRECISE
        )
        aimEngine = AimEngine(config)
        ballDetector = BallDetector()
        
        // 创建渲染视图
        setupOverlay()
        
        // 前台服务
        startForeground(NOTIFICATION_ID, createNotification())
    }
    
    private fun setupOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = FrameLayout(this)
        
        renderView = AssistRenderView(this, aimEngine, ballDetector)
        overlayView.addView(renderView)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        
        windowManager.addView(overlayView, params)
    }
    
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "灵喵辅助", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("灵喵 LingMiao")
            .setContentText("悬浮辅助运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startAssist()
            "STOP" -> stopAssist()
            "UPDATE_CONFIG" -> updateConfig(intent)
        }
        return START_STICKY  // 被杀后自动重启
    }
    
    private fun startAssist() {
        if (isRunning) return
        isRunning = true
        renderView.startRendering()
    }
    
    private fun stopAssist() {
        isRunning = false
        renderView.stopRendering()
    }
    
    private fun updateConfig(intent: Intent) {
        val compRatio = intent.getFloatExtra("comp_ratio", 0.18f)
        val maxBounces = intent.getIntExtra("max_bounces", 3)
        val lineWidth = intent.getFloatExtra("line_width", 5.0f)
        val showAnt = intent.getBooleanExtra("show_ant", false)
        val snapNearest = intent.getBooleanExtra("snap_nearest", true)
        
        val newConfig = AimEngine.AimConfig(
            compensationRatio = compRatio.toDouble(),
            maxBounces = maxBounces,
            lineWidth = lineWidth,
            lineColor = 0xFF00FF66.toInt(),
            showAntLine = showAnt,
            snapNearestBall = snapNearest,
            scheme = AimEngine.RecognitionScheme.PRECISE
        )
        // Rebuild engine with new config
        aimEngine = AimEngine(newConfig)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (overlayView.parent != null) {
            windowManager.removeView(overlayView)
        }
        isRunning = false
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    // ========== 渲染视图 ==========
    
    inner class AssistRenderView(
        context: android.content.Context,
        private val engine: AimEngine,
        private val detector: BallDetector
    ) : View(context) {
        
        private var rendering = false
        private var lastFrameTime = 0L
        
        fun startRendering() {
            rendering = true
            invalidate()
        }
        
        fun stopRendering() {
            rendering = false
        }
        
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            
            if (!rendering) return
            
            // 1. 从ScreenCaptureService获取最新帧
            val frame = ScreenCaptureService.getLatestFrame()
            if (frame != null) {
                // 2. 球检测
                val balls = detector.detectBalls(
                    frame.pixels, frame.width, frame.height
                )
                currentBalls = balls
                
                // 3. 识别母球
                cueBall = balls.find { it.type == AimEngine.BallType.CUE }
                
                // 4. 计算瞄准
                if (cueBall != null && balls.size > 1) {
                    val tableGeo = ScreenCaptureService.getCurrentTableGeometry()
                    if (tableGeo != null) {
                        currentAims = engine.findBestAim(
                            cueBall!!, balls, tableGeo,
                            AimEngine.AimMode.HYBRID
                        )
                        // 应用角度补偿
                        currentAims = currentAims.map { aim ->
                            engine.applyCompensation(aim, tableGeo)
                        }
                    }
                }
                
                // FPS
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime > 1000) {
                    currentFps = frameCount * 1000f / (now - lastFpsTime)
                    frameCount = 0
                    lastFpsTime = now
                }
            }
            
            // 5. 渲染
            val tableCorners = ScreenCaptureService.getTableCorners()
            val pockets = ScreenCaptureService.getCurrentPockets()
            
            val aimRenderer = AimRenderer(canvas, engine.config)
            aimRenderer.drawScene(
                cueBall = cueBall,
                balls = currentBalls,
                pockets = pockets ?: emptyList(),
                aims = currentAims,
                tableCorners = tableCorners
            )
            
            // 6. 调试信息
            if (BuildConfig.DEBUG) {
                aimRenderer.drawDebugInfo(
                    "FPS: ${currentFps.toInt()}\n" +
                    "Balls: ${currentBalls.size}\n" +
                    "Aims: ${currentAims.size}\n" +
                    "Mode: ${if (currentAims.firstOrNull()?.isBankShot == true) "BANK" else "DIRECT"}"
                )
            }
            
            // 7. 下一帧
            postDelayed({ invalidate() }, 16)  // ~60fps
        }
    }
}
