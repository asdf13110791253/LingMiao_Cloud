package com.lingmiao.engine

import android.graphics.*
import kotlin.math.*

/**
 * 瞄准辅助线渲染器
 * 
 * 在Canvas上绘制:
 * - 瞄准线 (实线/虚线)
 * - 反弹路线 (多段)
 * - 袋口标记
 * - 球位置标记
 * - 成功率/评分信息
 * - 蚂蚁线动画
 */
class AimRenderer(
    private val canvas: Canvas,
    private val config: AimEngine.AimConfig
) {
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    
    private var antOffset = 0f  // 蚂蚁线动画偏移
    private var lastFrameTime = System.currentTimeMillis()
    
    // ========== 颜色 ==========
    companion object {
        const val COLOR_CUE_BALL = 0xFFFFFFFF.toInt()
        const val COLOR_OBJECT_BALL = 0xFFFF4444.toInt()
        const val COLOR_AIM_LINE = 0xFF00FF66.toInt()
        const val COLOR_BANK_LINE = 0xFFFFAA00.toInt()
        const val COLOR_GHOST_BALL = 0x8800CCFF.toInt()
        const val COLOR_POCKET = 0xFFFF0088.toInt()
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_SCORE_HIGH = 0xFF00FF66.toInt()
        const val COLOR_SCORE_MED = 0xFFFFFF00.toInt()
        const val COLOR_SCORE_LOW = 0xFFFF4444.toInt()
    }
    
    // ========== 主绘制方法 ==========
    
    /**
     * 绘制完整的瞄准场景
     */
    fun drawScene(
        cueBall: AimEngine.Ball?,
        balls: List<AimEngine.Ball>,
        pockets: List<AimEngine.Pocket>,
        aims: List<AimEngine.AimLine>,
        tableCorners: Array<AimEngine.Vec2>?
    ) {
        // 更新动画
        updateAnimation()
        
        // 1. 球桌边框
        if (tableCorners != null) {
            drawTableBorder(tableCorners)
        }
        
        // 2. 袋口标记
        drawPockets(pockets)
        
        // 3. 所有球（半透明）
        drawBalls(balls)
        
        // 4. 瞄准线（最优的在前，半透明叠加）
        drawAimLines(aims)
        
        // 5. 母球高亮
        if (cueBall != null) {
            drawCueBall(cueBall)
        }
        
        // 6. 信息叠加
        drawInfoOverlay(aims)
    }
    
    // ========== 球桌 ==========
    
    private fun drawTableBorder(corners: Array<AimEngine.Vec2>) {
        paint.color = 0x66FFFFFF.toInt()
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        
        val path = Path()
        path.moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        for (i in 1..corners.size) {
            val c = corners[i % corners.size]
            path.lineTo(c.x.toFloat(), c.y.toFloat())
        }
        path.close()
        canvas.drawPath(path, paint)
    }
    
    // ========== 袋口 ==========
    
    private fun drawPockets(pockets: List<AimEngine.Pocket>) {
        for (p in pockets) {
            // 外圈
            paint.color = 0x44FF0088.toInt()
            paint.strokeWidth = 2f
            canvas.drawCircle(
                p.pos.x.toFloat(), p.pos.y.toFloat(),
                p.radius.toFloat() * 1.2f, paint
            )
            // 内圈
            fillPaint.color = 0x88000000.toInt()
            canvas.drawCircle(
                p.pos.x.toFloat(), p.pos.y.toFloat(),
                p.radius.toFloat() * 0.8f, fillPaint
            )
            // 类型标记
            textPaint.color = 0x88FFFFFF.toInt()
            textPaint.textSize = 18f
            val label = when(p.type) {
                AimEngine.PocketType.CORNER -> "C"
                AimEngine.PocketType.SIDE -> "S"
                else -> "P"
            }
            canvas.drawText(
                label, p.pos.x.toFloat() - 5f, p.pos.y.toFloat() + 6f, textPaint
            )
        }
    }
    
    // ========== 球 ==========
    
    private fun drawBalls(balls: List<AimEngine.Ball>) {
        for (ball in balls) {
            // 球的阴影
            fillPaint.color = 0x44000000.toInt()
            canvas.drawCircle(
                ball.pos.x.toFloat() + 2f,
                ball.pos.y.toFloat() + 2f,
                ball.radius.toFloat(), fillPaint
            )
            
            // 球本体
            fillPaint.color = ball.color
            canvas.drawCircle(
                ball.pos.x.toFloat(),
                ball.pos.y.toFloat(),
                ball.radius.toFloat(), fillPaint
            )
            
            // 球的光晕
            paint.color = 0x44FFFFFF.toInt()
            paint.strokeWidth = 1.5f
            canvas.drawCircle(
                ball.pos.x.toFloat(),
                ball.pos.y.toFloat(),
                ball.radius.toFloat() * 0.7f, paint
            )
            
            // 母球标记
            if (ball.type == AimEngine.BallType.CUE) {
                paint.color = COLOR_CUE_BALL
                paint.strokeWidth = 2f
                canvas.drawCircle(
                    ball.pos.x.toFloat(),
                    ball.pos.y.toFloat(),
                    ball.radius.toFloat() * 0.4f, paint
                )
            }
        }
    }
    
    private fun drawCueBall(cue: AimEngine.Ball) {
        // 母球外发光
        paint.color = 0x66FFFFFF.toInt()
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(
            cue.pos.x.toFloat(), cue.pos.y.toFloat(),
            cue.radius.toFloat() * 1.3f + sin(antOffset * 0.1f) * 3f, paint
        )
        paint.style = Paint.Style.FILL
    }
    
    // ========== 瞄准线 ==========
    
    private fun drawAimLines(aims: List<AimEngine.AimLine>) {
        if (aims.isEmpty()) return
        
        // 绘制多条路线（透明度递减）
        val maxDraw = min(aims.size, 3)
        for (i in 0 until maxDraw) {
            val aim = aims[i]
            val alpha = (255 * (1.0 - i * 0.3)).toInt().coerceIn(60, 255)
            
            val color = when {
                aim.isBankShot -> (alpha shl 24) or 0x00AAFF
                aim.isComboShot -> (alpha shl 24) or 0xFF00FF
                else -> (alpha shl 24) or 0x00FF66
            }
            
            paint.color = color
            paint.strokeWidth = if (i == 0) config.lineWidth else config.lineWidth * 0.6f
            
            if (config.showAntLine) {
                drawDashedPath(aim.viaPoints, paint, antOffset * (1f + i * 0.3f))
            } else {
                drawSolidPath(aim.viaPoints, paint)
            }
            
            // 反弹点标记
            if (aim.bounces > 0) {
                for (j in 1 until aim.viaPoints.size - 1) {
                    val p = aim.viaPoints[j]
                    paint.color = (alpha shl 24) or 0xFFAA00
                    paint.strokeWidth = 2f
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6f, paint)
                    // 反弹角度指示线
                    if (j < aim.viaPoints.size - 1) {
                        val next = aim.viaPoints[j+1]
                        val prev = aim.viaPoints[j-1]
                        val inDir = p.sub(prev).normalize()
                        val outDir = next.sub(p).normalize()
                        // 法线
                        val normal = AimEngine.Vec2(-inDir.y, inDir.x)
                        paint.color = (alpha shl 24) or 0x00CCFF
                        paint.strokeWidth = 1.5f
                        canvas.drawLine(
                            p.x.toFloat(), p.y.toFloat(),
                            (p.x + normal.x * 30).toFloat(),
                            (p.y + normal.y * 30).toFloat(), paint
                        )
                    }
                }
            }
            
            // 目标球撞击点标记
            if (aim.hitPoint != aim.start && aim.viaPoints.size >= 2) {
                val hitPoint = aim.hitPoint
                paint.color = 0xAAFF4444.toInt()
                paint.strokeWidth = 2f
                canvas.drawCircle(
                    hitPoint.x.toFloat(), hitPoint.y.toFloat(),
                    8f, paint
                )
            }
        }
        
        // 最优路线的详细信息
        val best = aims[0]
        drawAimDetails(best)
    }
    
    private fun drawSolidPath(points: List<AimEngine.Vec2>, p: Paint) {
        if (points.size < 2) return
        val path = Path()
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        canvas.drawPath(path, p)
    }
    
    private fun drawDashedPath(
        points: List<AimEngine.Vec2>, p: Paint, offset: Float
    ) {
        if (points.size < 2) return
        val dashLen = 12f
        val gapLen = 8f
        
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i+1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = sqrt(dx*dx + dy*dy)
            if (len < 1) continue
            
            val ux = dx / len
            val uy = dy / len
            
            var d = offset % (dashLen + gapLen)
            var pos = 0f
            
            while (pos < len) {
                val segEnd = min(len, pos + dashLen - (d % dashLen))
                if (segEnd > pos) {
                    canvas.drawLine(
                        (a.x + ux * pos).toFloat(),
                        (a.y + uy * pos).toFloat(),
                        (a.x + ux * segEnd).toFloat(),
                        (a.y + uy * segEnd).toFloat(),
                        p
                    )
                }
                pos = segEnd + gapLen
                d = 0f
            }
        }
    }
    
    // ========== 信息叠加 ==========
    
    private fun drawAimDetails(aim: AimEngine.AimLine) {
        // 成功率条
        val barW = 120f; val barH = 8f
        val bx = 20f; val by = 20f
        
        // 背景
        fillPaint.color = 0x44000000.toInt()
        canvas.drawRect(bx, by, bx + barW, by + barH, fillPaint)
        
        // 进度
        val scoreColor = when {
            aim.successRate > 0.7 -> COLOR_SCORE_HIGH
            aim.successRate > 0.4 -> COLOR_SCORE_MED
            else -> COLOR_SCORE_LOW
        }
        fillPaint.color = scoreColor
        canvas.drawRect(
            bx, by,
            bx + barW * aim.successRate.toFloat(),
            by + barH, fillPaint
        )
        
        // 文字
        textPaint.color = COLOR_TEXT
        textPaint.textSize = 22f
        canvas.drawText(
            "成功率: ${(aim.successRate*100).toInt()}%  切角: ${"%.1f".format(aim.cutAngle)}°",
            bx, by + barH + 22f, textPaint
        )
        
        // 路线类型
        val typeStr = when {
            aim.isBankShot -> "翻袋×${aim.bounces}"
            aim.isComboShot -> "借球"
            else -> "直接"
        }
        canvas.drawText(
            "路线: $typeStr  难度: ${(aim.difficulty*100).toInt()}%",
            bx, by + barH + 48f, textPaint
        )
        
        // 切球角度弧线
        drawCutAngleArc(aim)
    }
    
    private fun drawCutAngleArc(aim: AimEngine.AimLine) {
        if (aim.viaPoints.size < 2) return
        
        val center = aim.viaPoints[0]  // 母球
        val r = 50f
        val startAngle = aim.cutAngle.toFloat() / 2f
        val sweepAngle = aim.cutAngle.toFloat()
        
        paint.color = 0x66FFFFFF.toInt()
        paint.strokeWidth = 2f
        val rect = RectF(
            center.x.toFloat() - r, center.y.toFloat() - r,
            center.x.toFloat() + r, center.y.toFloat() + r
        )
        canvas.drawArc(rect, -startAngle - sweepAngle/2, sweepAngle, false, paint)
    }
    
    private fun drawInfoOverlay(aims: List<AimEngine.AimLine>) {
        if (aims.isEmpty()) {
            textPaint.color = 0x88FFFFFF.toInt()
            textPaint.textSize = 20f
            canvas.drawText("未找到可行路线", 20f, 80f, textPaint)
            return
        }
        
        // 路线数量
        textPaint.color = 0x66FFFFFF.toInt()
        textPaint.textSize = 18f
        canvas.drawText("共 ${aims.size} 条路线", 20f, 80f, textPaint)
    }
    
    // ========== 动画 ==========
    
    private fun updateAnimation() {
        val now = System.currentTimeMillis()
        val dt = (now - lastFrameTime) / 1000.0
        lastFrameTime = now
        antOffset += (dt * 60f).toFloat()  // 60px/sec
        if (antOffset > 10000f) antOffset -= 10000f
    }
    
    // ========== 辅助 ==========
    
    fun drawDebugInfo(info: String) {
        textPaint.color = 0xCC00FF66.toInt()
        textPaint.textSize = 16f
        val lines = info.split('\n')
        for (i in lines.indices) {
            canvas.drawText(lines[i], 20f, canvas.height - 20f - i * 20f, textPaint)
        }
    }
}
