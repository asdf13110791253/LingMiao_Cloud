package com.lingmiao.engine

import kotlin.math.*

/**
 * 校准引擎
 * 
 * 功能:
 * 1. 引导用户框选球桌四角
 * 2. 自动微调角点位置
 * 3. 计算透视变换矩阵
 * 4. 保存/加载校准参数
 * 5. 实时验证校准质量
 */
class CalibrationEngine {
    
    // ========== 校准状态 ==========
    enum class CalibState {
        IDLE,           // 未开始
        SELECTING,      // 用户拖角点
        REFINING,       // 自动微调
        VERIFYING,      // 验证中
        COMPLETED,      // 完成
        FAILED          // 失败
    }
    
    data class CalibResult(
        val corners: Array<AimEngine.Vec2>,  // 4角 (TL, TR, BR, BL)
        val homography: FloatArray,          // 3x3透视矩阵
        val inverseH: FloatArray,            // 逆矩阵
        val tableWidthMm: Double,            // 球桌物理宽度(mm)
        val tableHeightMm: Double,           // 球桌物理高度(mm)
        val confidence: Double,              // 校准置信度
        val state: CalibState
    )
    
    // ========== 角点微调 ==========
    
    /**
     * 自动微调角点位置
     * 基于边缘检测找到球桌布与库边的精确边界
     */
    fun refineCorners(
        corners: Array<AimEngine.Vec2>,
        frame: IntArray,
        width: Int,
        height: Int,
        maxIter: Int = 20
    ): Array<AimEngine.Vec2> {
        val refined = corners.copyOf()
        
        for (iter in 0 until maxIter) {
            for (i in refined.indices) {
                val c = refined[i]
                // 在角点附近搜索最佳边缘位置
                val bestOffset = searchEdgeOffset(c, frame, width, height, getSearchDirection(i))
                refined[i] = AimEngine.Vec2(
                    c.x + bestOffset.first,
                    c.y + bestOffset.second
                )
            }
        }
        
        return refined
    }
    
    /**
     * 在角点附近搜索边缘最强位置
     */
    private fun searchEdgeOffset(
        center: AimEngine.Vec2,
        frame: IntArray,
        w: Int, h: Int,
        direction: Pair<Double, Double>
    ): Pair<Double, Double> {
        val range = 30  // 搜索范围(px)
        val step = 2
        
        var bestOffset = Pair(0.0, 0.0)
        var maxEdge = 0.0
        
        val dirX = direction.first
        val dirY = direction.second
        
        for (s in -range..range step step) {
            val sx = (center.x + dirX * s).toInt()
            val sy = (center.y + dirY * s).toInt()
            
            if (sx < 2 || sx >= w-2 || sy < 2 || sy >= h-2) continue
            
            // Sobel边缘强度
            val gx = frame[sy*w+(sx+1)] - frame[sy*w+(sx-1)]
            val gy = frame[(sy+1)*w+sx] - frame[(sy-1)*w+sx]
            val edge = abs(gx) + abs(gy)
            
            if (edge > maxEdge) {
                maxEdge = edge.toDouble()
                bestOffset = Pair(dirX * s, dirY * s)
            }
        }
        
        return bestOffset
    }
    
    /**
     * 根据角点索引获取搜索方向
     * 角点应该向球桌内部搜索边缘
     */
    private fun getSearchDirection(cornerIdx: Int): Pair<Double, Double> {
        return when (cornerIdx) {
            0 -> Pair(1.0, 1.0)    // TL → 右下搜索
            1 -> Pair(-1.0, 1.0)   // TR → 左下搜索
            2 -> Pair(-1.0, -1.0)  // BR → 左上搜索
            3 -> Pair(1.0, -1.0)   // BL → 右上搜索
            else -> Pair(0.0, 0.0)
        }
    }
    
    // ========== 透视变换 ==========
    
    /**
     * 计算3x3 Homography矩阵
     */
    fun computeHomography(
        srcPts: Array<AimEngine.Vec2>,
        dstPts: Array<AimEngine.Vec2>
    ): FloatArray {
        // DLT (Direct Linear Transform)
        val A = Array(8) { FloatArray(9) }
        
        for (i in 0 until 4) {
            val x = srcPts[i].x.toFloat()
            val y = srcPts[i].y.toFloat()
            val X = dstPts[i].x.toFloat()
            val Y = dstPts[i].y.toFloat()
            
            A[i*2][0] = x; A[i*2][1] = y; A[i*2][2] = 1f
            A[i*2][3] = 0f; A[i*2][4] = 0f; A[i*2][5] = 0f
            A[i*2][6] = -X*x; A[i*2][7] = -X*y; A[i*2][8] = -X
            
            A[i*2+1][0] = 0f; A[i*2+1][1] = 0f; A[i*2+1][2] = 0f
            A[i*2+1][3] = x; A[i*2+1][4] = y; A[i*2+1][5] = 1f
            A[i*2+1][6] = -Y*x; A[i*2+1][7] = -Y*y; A[i*2+1][8] = -Y
        }
        
        // SVD求解
        val h = svdSolve(A)
        
        // 归一化为3x3矩阵
        val H = FloatArray(9)
        val norm = if (abs(h[8]) > 1e-10) h[8] else 1f
        for (i in 0 until 8) H[i] = h[i] / norm
        H[8] = 1f
        
        return H
    }
    
    /**
     * 透视变换点
     */
    fun transformPoint(
        p: AimEngine.Vec2, H: FloatArray
    ): AimEngine.Vec2 {
        val x = H[0]*p.x.toFloat() + H[1]*p.y.toFloat() + H[2]
        val y = H[3]*p.x.toFloat() + H[4]*p.y.toFloat() + H[5]
        val z = H[6]*p.x.toFloat() + H[7]*p.y.toFloat() + H[8]
        return AimEngine.Vec2(x/z.toDouble(), y/z.toDouble())
    }
    
    /**
     * 逆透视变换
     */
    fun invertHomography(H: FloatArray): FloatArray {
        // 3x3矩阵求逆
        val inv = FloatArray(9)
        
        val a = H[0]; val b = H[1]; val c = H[2]
        val d = H[3]; val e = H[4]; val f = H[5]
        val g = H[6]; val h = H[7]; val i = H[8]
        
        val det = a*(e*i - f*h) - b*(d*i - f*g) + c*(d*h - e*g)
        if (abs(det) < 1e-10) return H.copyOf()
        
        val invDet = 1f / det.toFloat()
        
        inv[0] = (e*i - f*h) * invDet
        inv[1] = (c*h - b*i) * invDet
        inv[2] = (b*f - c*e) * invDet
        inv[3] = (f*g - d*i) * invDet
        inv[4] = (a*i - c*g) * invDet
        inv[5] = (c*d - a*f) * invDet
        inv[6] = (d*h - e*g) * invDet
        inv[7] = (b*g - a*h) * invDet
        inv[8] = (a*e - b*d) * invDet
        
        return inv
    }
    
    // ========== 校准验证 ==========
    
    /**
     * 验证校准质量
     */
    fun verifyCalibration(
        corners: Array<AimEngine.Vec2>,
        frame: IntArray,
        width: Int,
        height: Int
    ): Double {
        var score = 0.0
        var checks = 0
        
        // 1. 检查球桌内区域是否为绿色（桌布）
        val tl = corners[0]; val br = corners[2]
        val samples = 20
        var greenCount = 0
        for (sy in 0..samples) {
            for (sx in 0..samples) {
                val fx = tl.x + (br.x - tl.x) * sx / samples
                val fy = tl.y + (br.y - tl.y) * sy / samples
                val ix = fx.toInt().coerceIn(0, width-1)
                val iy = fy.toInt().coerceIn(0, height-1)
                val p = frame[iy*width + ix]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 绿色判定
                if (g > r * 1.1 && g > b * 1.1) greenCount++
            }
        }
        val greenRatio = greenCount.toDouble() / ((samples+1)*(samples+1))
        score += min(1.0, greenRatio * 1.5)
        checks++
        
        // 2. 检查四边是否为直线（边缘强度沿边应该高）
        for (edge in 0..3) {
            val a = corners[edge]
            val b = corners[(edge+1)%4]
            val edgeStrength = measureEdgeStrength(a, b, frame, width, height)
            score += min(1.0, edgeStrength / 100.0)
            checks++
        }
        
        // 3. 长宽比检查
        val tw = corners[1].dist(corners[0])
        val th = corners[3].dist(corners[0])
        val ratio = tw / max(th, 1.0)
        val ratioErr = abs(ratio - 2.0) / 2.0  // 标准球桌 2:1
        score += max(0.0, 1.0 - ratioErr * 2)
        checks++
        
        return score / checks
    }
    
    private fun measureEdgeStrength(
        a: AimEngine.Vec2, b: AimEngine.Vec2,
        frame: IntArray, w: Int, h: Int
    ): Double {
        val samples = 30
        var totalEdge = 0
        for (i in 1 until samples) {
            val t = i.toDouble() / samples
            val x = (a.x + (b.x - a.x) * t).toInt()
            val y = (a.y + (b.y - a.y) * t).toInt()
            if (x < 1 || x >= w-1 || y < 1 || y >= h-1) continue
            val gx = abs(frame[y*w+(x+1)] - frame[y*w+(x-1)])
            val gy = abs(frame[(y+1)*w+x] - frame[(y-1)*w+x])
            totalEdge += gx + gy
        }
        return totalEdge.toDouble() / samples
    }
    
    // ========== SVD ==========
    
    private fun svdSolve(A: Array<FloatArray>): FloatArray {
        // 简化SVD - 高斯消元求最小二乘解
        val m = A.size
        val n = 9
        val M = Array(m) { FloatArray(n+1) }
        for (i in 0 until m) {
            for (j in 0 until n) M[i][j] = A[i][j].toDouble()
            M[i][n] = 0.0
        }
        
        // 正态方程 A^T * A * x = A^T * b
        val AtA = Array(n) { DoubleArray(n) }
        val Atb = DoubleArray(n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0.0
                for (k in 0 until m) sum += A[k][i] * A[k][j]
                AtA[i][j] = sum
            }
            var sum = 0.0
            for (k in 0 until m) sum += A[k][i] * 0.0
            Atb[i] = sum
        }
        
        // 高斯消元
        for (col in 0 until n) {
            var maxRow = col
            for (row in col+1 until n) {
                if (abs(AtA[row][col]) > abs(AtA[maxRow][col])) maxRow = row
            }
            val tmp = AtA[col]; AtA[col] = AtA[maxRow]; AtA[maxRow] = tmp
            val tmp2 = Atb[col]; Atb[col] = Atb[maxRow]; Atb[maxRow] = tmp2
            
            if (abs(AtA[col][col]) < 1e-12) continue
            
            for (row in col+1 until n) {
                val factor = AtA[row][col] / AtA[col][col]
                for (k in col until n) AtA[row][k] -= factor * AtA[col][k]
                Atb[row] -= factor * Atb[col]
            }
        }
        
        val x = DoubleArray(n)
        for (i in n-1 downTo 0) {
            if (abs(AtA[i][i]) < 1e-12) continue
            var sum = Atb[i]
            for (j in i+1 until n) sum -= AtA[i][j] * x[j]
            x[i] = sum / AtA[i][i]
        }
        
        return FloatArray(n) { x[it].toFloat() }
    }
}
