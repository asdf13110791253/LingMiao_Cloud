package com.lingmiao.ui

import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lingmiao.engine.AimEngine
import com.lingmiao.engine.CalibrationEngine
import com.lingmiao.service.ScreenCaptureService

/**
 * 校准Activity - 球桌四角精确对准
 * 
 * 用户拖动四个角点对齐球桌内缘
 * 实时显示校准质量和透视预览
 */
class CalibrationActivity : AppCompatActivity() {
    
    private lateinit var calibView: CalibView
    private lateinit var calibEngine: CalibrationEngine
    private var captureService: ScreenCaptureService? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        calibEngine = CalibrationEngine()
        
        calibView = CalibView(this)
        setContentView(calibView)
        
        // 获取屏幕捕获服务
        captureService = ScreenCaptureService.getInstance(this)
    }
    
    /**
     * 自定义视图 - 显示球桌画面 + 四个可拖动角点
     */
    inner class CalibView(context: android.content.Context) : View(context) {
        
        private val corners = arrayOf(
            AimEngine.Vec2(200.0, 300.0),   // TL
            AimEngine.Vec2(1000.0, 300.0),  // TR
            AimEngine.Vec2(1000.0, 1400.0), // BR
            AimEngine.Vec2(200.0, 1400.0)   // BL
        )
        private var dragging = -1
        private val paint = Paint().apply { isAntiAlias = true }
        private var latestFrame: IntArray? = null
        private var frameW = 0
        private var frameH = 0
        private var confidence = 0.0
        private var step = 0  // 0=选择角点 1=验证
        
        init {
            // 居中初始角点
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2
            val cy = metrics.heightPixels / 2
            val w = metrics.widthPixels * 0.7
            val h = metrics.heightPixels * 0.5
            corners[0] = AimEngine.Vec2(cx - w/2, cy - h/2)
            corners[1] = AimEngine.Vec2(cx + w/2, cy - h/2)
            corners[2] = AimEngine.Vec2(cx + w/2, cy + h/2)
            corners[3] = AimEngine.Vec2(cx - w/2, cy + h/2)
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 找最近的角点
                    var best = -1
                    var bestDist = 80.0  // 80px touch radius
                    for (i in corners.indices) {
                        val d = corners[i].dist(AimEngine.Vec2(event.x.toDouble(), event.y.toDouble()))
                        if (d < bestDist) {
                            bestDist = d
                            best = i
                        }
                    }
                    dragging = best
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging >= 0) {
                        corners[dragging] = AimEngine.Vec2(event.x.toDouble(), event.y.toDouble())
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    dragging = -1
                    // 自动微调
                    autoRefine()
                }
            }
            return true
        }
        
        private fun autoRefine() {
            // 获取最新帧并微调角点
            val frame = captureService?.getLatestFrame()
            if (frame != null) {
                val refined = calibEngine.refineCorners(
                    corners, frame.pixels, frame.width, frame.height
                )
                for (i in corners.indices) corners[i] = refined[i]
                
                // 验证
                confidence = calibEngine.verifyCalibration(
                    corners, frame.pixels, frame.width, frame.height
                )
                
                invalidate()
            }
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // 背景
            canvas.drawColor(0xFF0A0A1A.toInt())
            
            // 获取最新帧绘制
            val frame = captureService?.getLatestFrame()
            if (frame != null) {
                frameW = frame.width
                frameH = frame.height
                latestFrame = frame.pixels
                
                // 绘制屏幕内容（缩放适配）
                val bm = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                val scaleX = width.toFloat() / frame.width
                val scaleY = height.toFloat() / frame.height
                val scale = min(scaleX, scaleY)
                val dx = (width - frame.width * scale) / 2
                val dy = (height - frame.height * scale) / 2
                canvas.save()
                canvas.translate(dx, dy)
                canvas.scale(scale, scale)
                canvas.drawBitmap(bm, 0f, 0f, null)
                canvas.restore()
            }
            
            // 绘制球桌四边形
            paint.color = 0xAA00FF66.toInt()
            paint.strokeWidth = 3f
            paint.style = Paint.Style.STROKE
            val path = Path()
            path.moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
            for (i in 1..corners.size) {
                val c = corners[i % corners.size]
                path.lineTo(c.x.toFloat(), c.y.toFloat())
            }
            canvas.drawPath(path, paint)
            
            // 绘制角点
            for (i in corners.indices) {
                val c = corners[i]
                // 外圈
                paint.color = if (i == dragging) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
                paint.strokeWidth = 3f
                canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), 24f, paint)
                // 内圈填充
                paint.color = 0x8800BCD4.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), 16f, paint)
                paint.style = Paint.Style.STROKE
                // 标签
                paint.color = Color.WHITE
                paint.textSize = 20f
                val labels = arrayOf("TL", "TR", "BR", "BL")
                canvas.drawText(labels[i], c.x.toFloat() + 30f, c.y.toFloat() + 8f, paint)
            }
            
            // 绘制网格辅助线
            paint.color = 0x33FFFFFF.toInt()
            paint.strokeWidth = 1f
            // 中线
            val cx = (corners[0].x + corners[2].x) / 2
            val cy = (corners[0].y + corners[2].y) / 2
            canvas.drawLine(corners[0].x.toFloat(), cy.toFloat(), corners[1].x.toFloat(), cy.toFloat(), paint)
            canvas.drawLine(cx.toFloat(), corners[0].y.toFloat(), cx.toFloat(), corners[2].y.toFloat(), paint)
            
            // 信息文字
            paint.color = Color.WHITE
            paint.textSize = 24f
            canvas.drawText("拖动四角对齐球桌内缘", 40f, 60f, paint)
            paint.textSize = 18f
            paint.color = when {
                confidence > 0.8 -> 0xFF00FF66.toInt()
                confidence > 0.5 -> 0xFFFFFF00.toInt()
                else -> 0xFFFF4444.toInt()
            }
            canvas.drawText("校准质量: ${(confidence*100).toInt()}%", 40f, 90f, paint)
            
            // 按钮区域
            drawButtons(canvas)
            
            // 持续刷新
            postDelayed({ invalidate() }, 50)
        }
        
        private fun drawButtons(canvas: Canvas) {
            val btnY = height - 160f
            val btnH = 80f
            val margin = 40f
            val spacing = 20f
            val btnW = (width - 2 * margin - spacing) / 2
            val paint = Paint().apply { isAntiAlias = true }
            
            // 确认按钮
            paint.color = 0xFF4CAF50.toInt()
            canvas.drawRoundRect(margin, btnY, margin + btnW, btnY + btnH, 16f, 16f, paint)
            paint.color = Color.WHITE
            paint.textSize = 22f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("确认校准", margin + btnW/2, btnY + btnH/2 + 8f, paint)
            
            // 重新校准按钮
            paint.color = 0xFF2196F3.toInt()
            canvas.drawRoundRect(margin + btnW + spacing, btnY, width - margin, btnY + btnH, 16f, 16f, paint)
            canvas.drawText("重新选择", margin + btnW + spacing + btnW/2, btnY + btnH/2 + 8f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        
        fun handleTap(x: Float, y: Float): Boolean {
            val btnY = height - 160f
            val btnH = 80f
            val margin = 40f
            val spacing = 20f
            val btnW = (width - 2 * margin - spacing) / 2
            
            // 确认按钮
            if (x >= margin && x <= margin + btnW && y >= btnY && y <= btnY + btnH) {
                // 保存校准
                val prefs = context.getSharedPreferences("lingmiao", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("calibrated", true)
                    putFloat("calib_tl_x", corners[0].x.toFloat())
                    putFloat("calib_tl_y", corners[0].y.toFloat())
                    putFloat("calib_tr_x", corners[1].x.toFloat())
                    putFloat("calib_tr_y", corners[1].y.toFloat())
                    putFloat("calib_br_x", corners[2].x.toFloat())
                    putFloat("calib_br_y", corners[2].y.toFloat())
                    putFloat("calib_bl_x", corners[3].x.toFloat())
                    putFloat("calib_bl_y", corners[3].y.toFloat())
                    putFloat("calib_confidence", confidence.toFloat())
                    apply()
                }
                
                Toast.makeText(context, "校准成功！", Toast.LENGTH_SHORT).show()
                (context as CalibrationActivity).finish()
                return true
            }
            
            // 重新选择
            if (x >= margin + btnW + spacing && x <= width - margin && y >= btnY && y <= btnY + btnH) {
                // 重置角点
                val metrics = resources.displayMetrics
                val cx = metrics.widthPixels / 2
                val cy = metrics.heightPixels / 2
                val w = metrics.widthPixels * 0.6
                val h = metrics.heightPixels * 0.45
                corners[0] = AimEngine.Vec2(cx - w/2, cy - h/2)
                corners[1] = AimEngine.Vec2(cx + w/2, cy - h/2)
                corners[2] = AimEngine.Vec2(cx + w/2, cy + h/2)
                corners[3] = AimEngine.Vec2(cx - w/2, cy + h/2)
                confidence = 0.0
                invalidate()
                return true
            }
            
            return false
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            calibView.handleTap(event.x, event.y)
        }
        return super.onTouchEvent(event)
    }
}
