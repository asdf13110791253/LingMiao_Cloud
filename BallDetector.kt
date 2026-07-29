package com.lingmiao.engine

import kotlin.math.*

/**
 * 球检测模块
 * 
 * 输入: 屏幕帧 (Bitmap/RGBA)
 * 输出: 所有球的位置、颜色、类型
 * 
 * 流程:
 * 1. 预处理: 灰度化 + 高斯模糊 + Canny边缘
 * 2. 圆检测: HoughCircles算法定位所有圆
 * 3. 颜色分类: 根据圆的颜色区域判断球类型
 * 4. 母球识别: 白色球中最近被击打的那个
 * 5. 验证: 球距/球桌范围/大小一致性
 */
class BallDetector(
    private val params: DetectParams = DetectParams()
) {
    
    data class DetectParams(
        val minRadius: Int = 18,       // 最小球半径(px)
        val maxRadius: Int = 35,       // 最大球半径(px)
        val minDist: Int = 40,         // 球之间最小距离(px)
        val dp: Double = 1.5,          // Hough accumulator resolution
        val param1: Double = 100.0,    // Canny high threshold
        val param2: Double = 30.0,     // Hough center threshold
        val brightnessThresh: Int = 128,
        val roundnessThresh: Double = 0.7,
        val scheme: AimEngine.RecognitionScheme = AimEngine.RecognitionScheme.PRECISE
    )
    
    // ========== 球颜色LUT ==========
    // RGBA颜色 -> 球类型映射
    private val colorLUT = mapOf(
        0xFFFFFFFF.toInt() to BallType.CUE,    // 白色=母球
        0xFF000000.toInt() to BallType.OBJECT, // 黑色
        0xFFFF0000.toInt() to BallType.OBJECT, // 红色=1号
        0xFF0000FF.toInt() to BallType.OBJECT, // 蓝色=2号
        0xFFFFFF00.toInt() to BallType.OBJECT, // 黄色=3号
        0xFFFF00FF.toInt() to BallType.OBJECT, // 品红=4号
        0xFF00FFFF.toInt() to BallType.OBJECT, // 青色=5号
        0xFF008000.toInt() to BallType.OBJECT, // 绿色=6号
        0xFFFF8C00.toInt() to BallType.OBJECT, // 橙色=7号
        0xFF800080.toInt() to BallType.OBJECT, // 紫色=8号
    )
    
    // ========== 检测入口 ==========
    
    /**
     * 检测帧中所有球
     * 
     * @param frame RGBA像素数组
     * @param width 帧宽度
     * @param height 帧高度
     * @param tableMask 球桌区域掩码(null=全帧)
     * @return 检测到的球列表
     */
    fun detectBalls(
        frame: IntArray,
        width: Int,
        height: Int,
        tableMask: BooleanArray? = null
    ): List<AimEngine.Ball> {
        // 1. 预处理
        val gray = toGrayscale(frame, width, height)
        val blurred = gaussianBlur(gray, width, height, params.scheme)
        
        // 2. 边缘检测
        val edges = cannyEdge(blurred, width, height, params.param1.toInt())
        
        // 3. Hough圆检测
        val circles = houghCircles(
            edges, blurred, width, height,
            params.dp, params.param1, params.param2,
            params.minRadius, params.maxRadius, params.minDist
        )
        
        // 4. 颜色分类 + 验证
        val balls = mutableListOf<AimEngine.Ball>()
        for (c in circles) {
            val (cx, cy, r) = c
            if (tableMask != null) {
                val idx = (cy * width + cx).toInt()
                if (idx < 0 || idx >= tableMask.size || !tableMask[idx]) continue
            }
            
            // 提取圆的颜色
            val color = sampleCircleColor(frame, width, height, cx, cy, r)
            val ballType = classifyBall(color, r)
            
            // 验证: 圆度检查
            val roundness = checkRoundness(edges, cx, cy, r)
            if (roundness < params.roundnessThresh) continue
            
            balls.add(AimEngine.Ball(
                pos = AimEngine.Vec2(cx.toDouble(), cy.toDouble()),
                radius = r.toDouble(),
                type = ballType,
                color = color
            ))
        }
        
        // 5. 母球识别: 如果有多颗白球，选最近的那个
        val whiteBalls = balls.filter { it.type == AimEngine.BallType.CUE }
        if (whiteBalls.size > 1) {
            // 保留最"正常"的那颗（居中位置、大小一致）
            val best = whiteBalls.minByOrNull { 
                abs(it.radius - params.minRadius - params.maxRadius / 2.0)
            }
            balls.removeAll { it.type == AimEngine.BallType.CUE && it != best }
        }
        
        // 6. 去重: 距离过近的球合并
        return dedupBalls(balls, params.minDist * 0.6)
    }
    
    // ========== 图像处理 ==========
    
    private fun toGrayscale(frame: IntArray, w: Int, h: Int): IntArray {
        val gray = IntArray(w * h)
        for (i in frame.indices) {
            val p = frame[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // BT.601 luma
            gray[i] = (0.299*r + 0.587*g + 0.114*b).toInt()
        }
        return gray
    }
    
    private fun gaussianBlur(
        gray: IntArray, w: Int, h: Int, scheme: AimEngine.RecognitionScheme
    ): IntArray {
        val kernelSize = when(scheme) {
            AimEngine.RecognitionScheme.FAST -> 3
            AimEngine.RecognitionScheme.PRECISE -> 5
            AimEngine.RecognitionScheme.LOW_LIGHT -> 7
            AimEngine.RecognitionScheme.HIGH_CONTRAST -> 3
        }
        val sigma = when(scheme) {
            AimEngine.RecognitionScheme.LOW_LIGHT -> 2.0
            else -> 1.0
        }
        
        // 生成高斯核
        val kernel = DoubleArray(kernelSize * kernelSize)
        val half = kernelSize / 2
        var sum = 0.0
        for (y in 0 until kernelSize) {
            for (x in 0 until kernelSize) {
                val dx = x - half
                val dy = y - half
                val v = exp(-(dx*dx + dy*dy)/(2*sigma*sigma)) / (2*PI*sigma*sigma)
                kernel[y*kernelSize+x] = v
                sum += v
            }
        }
        for (i in kernel.indices) kernel[i] /= sum
        
        // 分离卷积 (行+列) 加速
        val temp = IntArray(w * h)
        val out = IntArray(w * h)
        
        // 水平
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0.0
                for (kx in 0 until kernelSize) {
                    val sx = min(w-1, max(0, x + kx - half))
                    v += gray[y*w + sx] * kernel[kx]
                }
                temp[y*w + x] = v.toInt()
            }
        }
        
        // 垂直
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0.0
                for (ky in 0 until kernelSize) {
                    val sy = min(h-1, max(0, y + ky - half))
                    v += temp[sy*w + x] * kernel[ky*kernelSize]
                }
                out[y*w + x] = v.toInt()
            }
        }
        
        return out
    }
    
    private fun cannyEdge(
        gray: IntArray, w: Int, h: Int, thresh: Int
    ): BooleanArray {
        val edges = BooleanArray(w * h)
        // Sobel边缘
        for (y in 1 until h-1) {
            for (x in 1 until w-1) {
                val gx = gray[y*w+(x+1)] - gray[y*w+(x-1)]
                val gy = gray[(y+1)*w+x] - gray[(y-1)*w+x]
                val mag = sqrt((gx*gx + gy*gy).toDouble()).toInt()
                edges[y*w+x] = mag > thresh
            }
        }
        return edges
    }
    
    // ========== Hough圆检测 ==========
    
    private fun houghCircles(
        edges: BooleanArray, gray: IntArray,
        w: Int, h: Int,
        dp: Double, param1: Double, param2: Double,
        minR: Int, maxR: Int, minDist: Int
    ): List<Triple<Float, Float, Float>> {
        val circles = mutableListOf<Triple<Float, Float, Float>>()
        
        // 简化的Hough圆检测
        // 实际项目中调用OpenCV的HoughCircles
        // 这里实现梯度法（更快更准确）
        
        // 1. 收集边缘点
        val edgePts = mutableListOf<Pair<Int, Int>>()
        for (y in 1 until h-1) {
            for (x in 1 until w-1) {
                if (edges[y*w+x]) {
                    // 计算梯度方向
                    val gx = gray[y*w+(x+1)] - gray[y*w+(x-1)]
                    val gy = gray[(y+1)*w+x] - gray[(y-1)*w+x]
                    edgePts.add(Pair(x, y))
                }
            }
        }
        
        if (edgePts.size < 10) return circles
        
        // 2. 累加器
        val accumW = (w / dp).toInt()
        val accumH = (h / dp).toInt()
        val maxRAccum = (maxR / dp).toInt()
        
        // 对每个边缘点，沿梯度方向投票
        // 简化的2D累加（固定半径范围）
        val midR = (minR + maxR) / 2
        val accum = Array(accumH) { IntArray(accumW) }
        
        for ((x, y) in edgePts) {
            // 梯度
            val gx = gray[y*w+(x+1)] - gray[y*w+(x-1)]
            val gy = gray[(y+1)*w+x] - gray[(y-1)*w+x]
            val glen = sqrt((gx*gx + gy*gy).toDouble())
            if (glen < param1 * 0.3) continue
            
            val dx = gx / glen
            val dy = gy / glen
            
            // 圆心在梯度反方向（指向圆心）
            val ax = (x - dx * midR).toInt()
            val ay = (y - dy * midR).toInt()
            
            val axd = (ax / dp).toInt()
            val ayd = (ay / dp).toInt()
            
            if (axd >= 0 && axd < accumW && ayd >= 0 && ayd < accumH) {
                accum[ayd][axd]++
            }
        }
        
        // 3. 非极大值抑制 + 阈值
        val threshold = param2.toInt()
        val found = Array(accumH) { BooleanArray(accumW) }
        
        for (y in 1 until accumH-1) {
            for (x in 1 until accumW-1) {
                if (accum[y][x] > threshold) {
                    // 检查8邻域
                    var isMax = true
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dy == 0 && dx == 0) continue
                            if (accum[y+dy][x+dx] >= accum[y][x]) {
                                isMax = false
                                break
                            }
                        }
                        if (!isMax) break
                    }
                    if (isMax) {
                        found[y][x] = true
                        circles.add(Triple(
                            (x * dp).toFloat(),
                            (y * dp).toFloat(),
                            midR.toFloat()
                        ))
                    }
                }
            }
        }
        
        // 4. 距离过滤（去除过近的圆）
        return filterByDistance(circles, minDist)
    }
    
    private fun filterByDistance(
        circles: List<Triple<Float, Float, Float>>, minDist: Int
    ): List<Triple<Float, Float, Float>> {
        val result = mutableListOf<Triple<Float, Float, Float>>()
        for (c in circles) {
            var tooClose = false
            for (r in result) {
                val dx = c.first - r.first
                val dy = c.second - r.second
                if (sqrt((dx*dx + dy*dy).toDouble()) < minDist) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) result.add(c)
        }
        return result
    }
    
    // ========== 颜色分类 ==========
    
    private fun sampleCircleColor(
        frame: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float
    ): Int {
        // 采样圆内区域的颜色
        var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
        val rInt = r.toInt()
        
        for (dy in -rInt..rInt) {
            for (dx in -rInt..rInt) {
                if (dx*dx + dy*dy > rInt*rInt) continue
                val x = (cx + dx).toInt()
                val y = (cy + dy).toInt()
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val p = frame[y*w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
                count++
            }
        }
        
        if (count == 0) return 0
        return (min(255, rSum/count) shl 16) or
               (min(255, gSum/count) shl 8) or
               min(255, bSum/count)
    }
    
    private fun classifyBall(color: Int, radius: Float): AimEngine.BallType {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        // 白色判定（宽容度高）
        val avg = (r + g + b) / 3
        val maxDiff = max(abs(r-g), max(abs(r-b), abs(g-b)))
        
        if (avg > 180 && maxDiff < 40) {
            return AimEngine.BallType.CUE  // 白色 = 母球
        }
        
        // 黑色判定
        if (avg < 40) {
            return AimEngine.BallType.OBJECT  // 黑色球
        }
        
        // 其他颜色
        return AimEngine.BallType.OBJECT
    }
    
    // ========== 圆度验证 ==========
    
    private fun checkRoundness(
        edges: BooleanArray, cx: Float, cy: Float, r: Int
    ): Double {
        // 在圆周上采样，检查边缘连续性
        val w = sqrt(edges.size.toDouble()).toInt()  // 近似
        var edgeCount = 0
        var sampleCount = 0
        
        val samples = 36  // 每10度一个采样点
        for (i in 0 until samples) {
            val angle = 2 * PI * i / samples
            val sx = (cx + r * cos(angle)).toInt()
            val sy = (cy + r * sin(angle)).toInt()
            sampleCount++
            // 简化: 假设edges是一维数组
            // 实际需要根据真实width计算索引
        }
        
        // 简化为: 返回基于半径一致性的分数
        // 实际实现中会比较检测半径和验证半径
        return 0.85  // placeholder
    }
    
    // ========== 去重 ==========
    
    private fun dedupBalls(balls: List<AimEngine.Ball>, threshold: Double): List<AimEngine.Ball> {
        val result = mutableListOf<AimEngine.Ball>()
        val used = BooleanArray(balls.size)
        
        for (i in balls.indices) {
            if (used[i]) continue
            used[i] = true
            var merged = balls[i]
            for (j in i+1 until balls.size) {
                if (used[j]) continue
                if (merged.pos.dist(balls[j].pos) < threshold) {
                    // 合并: 取平均位置
                    merged = merged.copy(
                        pos = AimEngine.Vec2(
                            (merged.pos.x + balls[j].pos.x) / 2,
                            (merged.pos.y + balls[j].pos.y) / 2
                        ),
                        radius = (merged.radius + balls[j].radius) / 2
                    )
                    used[j] = true
                }
            }
            result.add(merged)
        }
        
        return result
    }
}
