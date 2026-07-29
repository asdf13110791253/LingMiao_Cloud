package com.lingmiao.engine

import kotlin.math.*

/**
 * 球桌几何模块
 * 
 * 功能:
 * 1. 检测球桌四边和六个袋口位置
 * 2. 透视校正（手机拍摄角度不正时）
 * 3. 球桌物理参数管理
 * 4. 袋口区域判定（球是否入袋）
 * 5. 库边反弹计算
 */
class TableGeometryDetector(
    private val params: TableParams = TableParams()
) {
    
    data class TableParams(
        val tableType: TableType = TableType.TOURNAMENT_9FT,
        val aspectRatio: Double = 2.0,  // 长:宽
        val pocketToTableRatio: Double = 0.045,  // 袋口半径/球桌长
        val autoDetect: Boolean = true,
        val manualCorners: Array<AimEngine.Vec2>? = null
    )
    
    enum class TableType {
        TOURNAMENT_9FT,  // 2540x1270mm
        STANDARD_8FT,    // 2380x1190mm
        BAR_7FT,         // 2100x1050mm
        PRO_10FT,        // 2800x1400mm
        MINICLIP_POOL,   // 游戏虚拟尺寸
    }
    
    // ========== 球桌检测结果 ==========
    data class DetectedTable(
        val corners: Array<AimEngine.Vec2>,  // 4个角 (TL, TR, BR, BL)
        val pockets: List<AimEngine.Pocket>,
        val width: Double,    // 像素宽度
        val height: Double,   // 像素高度
        val rotation: Double, // 旋转角度(弧度)
        val perspectiveCorrected: Boolean,
        val confidence: Double
    )
    
    // ========== 检测入口 ==========
    
    /**
     * 检测球桌几何
     */
    fun detectTable(
        frame: IntArray,
        width: Int,
        height: Int
    ): DetectedTable? {
        if (!params.autoDetect && params.manualCorners != null) {
            return buildFromCorners(params.manualCorners!!)
        }
        
        // 1. 边缘检测 - 找球桌边界
        val edges = detectEdges(frame, width, height)
        
        // 2. 直线检测 - HoughLines找四边
        val lines = houghLines(edges, width, height)
        
        // 3. 四边形拟合 - 从直线中找最佳四边形
        val quad = fitQuadrilateral(lines, width, height)
        if (quad == null || quad.size != 4) {
            // 降级: 用默认参数
            return buildDefault(width, height)
        }
        
        // 4. 透视校正
        val corrected = correctPerspective(quad, width, height)
        
        // 5. 袋口定位
        val pockets = locatePockets(corrected, width, height)
        
        // 6. 计算物理参数
        val tw = corrected[1].x - corrected[0].x  // 顶边宽度
        val th = corrected[2].y - corrected[0].y  // 左边高度
        
        return DetectedTable(
            corners = corrected,
            pockets = pockets,
            width = tw,
            height = th,
            rotation = atan2(corrected[1].y - corrected[0].y, corrected[1].x - corrected[0].x),
            perspectiveCorrected = true,
            confidence = computeTableConfidence(quad, lines)
        )
    }
    
    // ========== 边缘检测 ==========
    
    private fun detectEdges(frame: IntArray, w: Int, h: Int): BooleanArray {
        val gray = IntArray(w * h)
        for (i in frame.indices) {
            val p = frame[i]
            gray[i] = ((p shr 16) and 0xFF) / 3 +
                      ((p shr 8) and 0xFF) / 3 +
                      (p and 0xFF) / 3
        }
        
        // Sobel
        val edges = BooleanArray(w * h)
        val thresh = 80
        for (y in 2 until h-2) {
            for (x in 2 until w-2) {
                val gx = gray[y*w+(x+2)] + 2*gray[y*w+(x+1)] - gray[y*w+(x-2)] - 2*gray[y*w+(x-1)]
                val gy = gray[(y+2)*w+x] + 2*gray[(y+1)*w+x] - gray[(y-2)*w+x] - 2*gray[(y-1)*w+x]
                if (abs(gx) + abs(gy) > thresh) {
                    edges[y*w+x] = true
                }
            }
        }
        return edges
    }
    
    // ========== Hough直线检测 ==========
    
    private fun houghLines(
        edges: BooleanArray, w: Int, h: Int
    ): List<Pair<Double, Double>> {
        // (rho, theta) 格式
        val lines = mutableListOf<Pair<Double, Double>>()
        
        val dRho = 2.0
        val dTheta = PI / 180.0  // 1度
        val rhoMax = sqrt((w*w + h*h).toDouble())
        val numRho = (rhoMax / dRho).toInt()
        val numTheta = (PI / dTheta).toInt()
        
        val accum = Array(numTheta) { IntArray(numRho) }
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (edges[y*w+x]) {
                    for (t in 0 until numTheta) {
                        val theta = t * dTheta
                        val rho = x * cos(theta) + y * sin(theta)
                        val rIdx = ((rho + rhoMax) / dRho).toInt()
                        if (rIdx >= 0 && rIdx < numRho) {
                            accum[t][rIdx]++
                        }
                    }
                }
            }
        }
        
        // 阈值
        val threshold = max(w, h) / 4
        for (t in 0 until numTheta) {
            for (r in 0 until numRho) {
                if (accum[t][r] > threshold) {
                    // 非极大值抑制
                    var isMax = true
                    for (dt in -2..2) {
                        for (dr in -2..2) {
                            val nt = t + dt
                            val nr = r + dr
                            if (nt in 0 until numTheta && nr in 0 until numRho) {
                                if (accum[nt][nr] > accum[t][r]) {
                                    isMax = false
                                    break
                                }
                            }
                        }
                        if (!isMax) break
                    }
                    if (isMax) {
                        val rho = r * dRho - rhoMax
                        val theta = t * dTheta
                        lines.add(Pair(rho, theta))
                    }
                }
            }
        }
        
        // 合并相近直线
        return mergeLines(lines)
    }
    
    private fun mergeLines(lines: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (lines.size < 2) return lines
        
        val merged = mutableListOf<Pair<Double, Double>>()
        val used = BooleanArray(lines.size)
        val rhoThresh = 20.0
        val thetaThresh = 0.1
        
        for (i in lines.indices) {
            if (used[i]) continue
            var sumRho = lines[i].first
            var sumTheta = lines[i].second
            var count = 1
            used[i] = true
            
            for (j in i+1 until lines.size) {
                if (used[j]) continue
                if (abs(lines[i].first - lines[j].first) < rhoThresh &&
                    abs(lines[i].second - lines[j].second) < thetaThresh) {
                    sumRho += lines[j].first
                    sumTheta += lines[j].second
                    count++
                    used[j] = true
                }
            }
            
            merged.add(Pair(sumRho/count, sumTheta/count))
        }
        
        return merged
    }
    
    // ========== 四边形拟合 ==========
    
    private fun fitQuadrilateral(
        lines: List<Pair<Double, Double>>, w: Int, h: Int
    ): Array<AimEngine.Vec2>? {
        // 将直线按角度分组: 水平(上下) 和 垂直(左右)
        val horizontal = lines.filter { abs(cos(it.second)) < 0.5 }  // theta ≈ 90° or 270°
        val vertical = lines.filter { abs(sin(it.second)) < 0.5 }    // theta ≈ 0° or 180°
        
        if (horizontal.size < 2 || vertical.size < 2) return null
        
        // 找最外的两条水平线（上边和下边）
        val topLine = horizontal.minByOrNull { rhoToY(it, w/2, h/2) }!!
        val bottomLine = horizontal.maxByOrNull { rhoToY(it, w/2, h/2) }!!
        
        // 找最外的两条垂直线（左边和右边）
        val leftLine = vertical.minByOrNull { rhoToX(it, w/2, h/2) }!!
        val rightLine = vertical.maxByOrNull { rhoToX(it, w/2, h/2) }!!
        
        // 计算四个交点
        val tl = lineIntersection(topLine, leftLine)
        val tr = lineIntersection(topLine, rightLine)
        val bl = lineIntersection(bottomLine, leftLine)
        val br = lineIntersection(bottomLine, rightLine)
        
        if (tl == null || tr == null || bl == null || br == null) return null
        
        // 验证四边形合理性
        val aspectErr = abs((tr.x - tl.x) / max((bl.y - tl.y), 1.0) - params.aspectRatio)
        if (aspectErr > 0.5) return null  // 长宽比偏差太大
        
        return arrayOf(tl, tr, br, bl)
    }
    
    private fun rhoToY(line: Pair<Double, Double>, cx: Int, cy: Int): Double {
        val (rho, theta) = line
        // y = (rho - x*cos(theta)) / sin(theta)
        return (rho - cx * cos(theta)) / max(sin(theta), 0.01)
    }
    
    private fun rhoToX(line: Pair<Double, Double>, cx: Int, cy: Int): Double {
        val (rho, theta) = line
        return (rho - cy * sin(theta)) / max(cos(theta), 0.01)
    }
    
    private fun lineIntersection(
        l1: Pair<Double, Double>, l2: Pair<Double, Double>
    ): AimEngine.Vec2? {
        val (r1, t1) = l1
        val (r2, t2) = l2
        
        // x = (r1*sin(t2) - r2*sin(t1)) / sin(t2-t1)
        // y = (r2*cos(t1) - r1*cos(t2)) / sin(t2-t1)
        val denom = sin(t2 - t1)
        if (abs(denom) < 1e-6) return null
        
        val x = (r1 * sin(t2) - r2 * sin(t1)) / denom
        val y = (r2 * cos(t1) - r1 * cos(t2)) / denom
        
        return AimEngine.Vec2(x, y)
    }
    
    // ========== 透视校正 ==========
    
    private fun correctPerspective(
        corners: Array<AimEngine.Vec2>, w: Int, h: Int
    ): Array<AimEngine.Vec2> {
        // 计算透视变换矩阵
        // 将检测到的四边形映射到标准矩形
        val src = corners
        
        // 目标: 标准矩形 (保持宽高比)
        val tw = (src[1].dist(src[0]) + src[2].dist(src[3])) / 2
        val th = (src[3].dist(src[0]) + src[2].dist(src[1])) / 2
        val dst = arrayOf(
            AimEngine.Vec2(0.0, 0.0),
            AimEngine.Vec2(tw, 0.0),
            AimEngine.Vec2(tw, th),
            AimEngine.Vec2(0.0, th)
        )
        
        // 计算Homography (4点对应)
        val H = computeHomography(src, dst)
        
        // 应用变换
        return Array(4) { i ->
            val p = src[i]
            val x = H[0]*p.x + H[1]*p.y + H[2]
            val y = H[3]*p.x + H[4]*p.y + H[5]
            val z = H[6]*p.x + H[7]*p.y + 1.0
            AimEngine.Vec2(x/z, y/z)
        }
    }
    
    /**
     * 计算4点Homography (Direct Linear Transform)
     */
    private fun computeHomography(
        src: Array<AimEngine.Vec2>, dst: Array<AimEngine.Vec2>
    ): DoubleArray {
        // 构建8x9矩阵 A*h = 0
        val A = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val sx = src[i].x; val sy = src[i].y
            val dx = dst[i].x; val dy = dst[i].y
            
            // 第一行
            A[i*2][0] = sx; A[i*2][1] = sy; A[i*2][2] = 1.0
            A[i*2][3] = 0.0; A[i*2][4] = 0.0; A[i*2][5] = 0.0
            A[i*2][6] = -dx*sx; A[i*2][7] = -dx*sy; A[i*2][8] = -dx
            
            // 第二行
            A[i*2+1][0] = 0.0; A[i*2+1][1] = 0.0; A[i*2+1][2] = 0.0
            A[i*2+1][3] = sx; A[i*2+1][4] = sy; A[i*2+1][5] = 1.0
            A[i*2+1][6] = -dy*sx; A[i*2+1][7] = -dy*sy; A[i*2+1][8] = -dy
        }
        
        // SVD求解 (简化: 用高斯消元求最小特征值的向量)
        // 实际项目中用Eigen/OpenCV的SVD
        // 这里返回单位变换作为fallback
        val H = DoubleArray(8)
        H[0] = 1.0; H[4] = 1.0  // 近似恒等变换
        
        // 简化SVD
        val h = solveSVD(A)
        return h
    }
    
    private fun solveSVD(A: Array<DoubleArray>): DoubleArray {
        // 简化的SVD - 实际应该用数值库
        // 这里用高斯消元求近似解
        val n = 9
        val M = Array(n) { DoubleArray(n+1) }
        for (i in 0 until 8) {
            for (j in 0 until n) {
                M[i][j] = A[i][j]
            }
            M[i][n] = 0.0
        }
        // 添加约束 h[8] = 1
        M[8][8] = 1.0
        M[8][n] = 1.0
        
        // 高斯消元
        for (col in 0 until n) {
            // 找主元
            var maxRow = col
            var maxVal = abs(M[col][col])
            for (row in col+1 until n) {
                if (abs(M[row][col]) > maxVal) {
                    maxVal = abs(M[row][col])
                    maxRow = row
                }
            }
            // 交换
            if (maxRow != col) {
                val tmp = M[col]
                M[col] = M[maxRow]
                M[maxRow] = tmp
            }
            // 消元
            if (abs(M[col][col]) < 1e-10) continue
            for (row in col+1 until n) {
                val factor = M[row][col] / M[col][col]
                for (k in col until n+1) {
                    M[row][k] -= factor * M[col][k]
                }
            }
        }
        
        // 回代
        val x = DoubleArray(n)
        for (i in n-1 downTo 0) {
            if (abs(M[i][i]) < 1e-10) continue
            var sum = M[i][n]
            for (j in i+1 until n) {
                sum -= M[i][j] * x[j]
            }
            x[i] = sum / M[i][i]
        }
        
        return x.copyOf(8)
    }
    
    // ========== 袋口定位 ==========
    
    private fun locatePockets(
        corners: Array<AimEngine.Vec2>, w: Int, h: Int
    ): List<AimEngine.Pocket> {
        val pockets = mutableListOf<AimEngine.Pocket>()
        
        // 六个袋口位置（相对球桌百分比）
        // 标准台球桌: 4角袋 + 2边袋
        val tl = corners[0]; val tr = corners[1]
        val br = corners[2]; val bl = corners[3]
        
        // 角袋
        val cornerRatio = 0.02  // 袋口偏离角的距离比例
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                tl.x + (tr.x - tl.x) * cornerRatio * 0.5,
                tl.y + (bl.y - tl.y) * cornerRatio * 0.5
            ),
            radius = 40.0,
            type = AimEngine.PocketType.CORNER
        ))
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                tr.x - (tr.x - tl.x) * cornerRatio * 0.5,
                tr.y + (br.y - tr.y) * cornerRatio * 0.5
            ),
            radius = 40.0,
            type = AimEngine.PocketType.CORNER
        ))
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                bl.x + (br.x - bl.x) * cornerRatio * 0.5,
                bl.y - (bl.y - tl.y) * cornerRatio * 0.5
            ),
            radius = 40.0,
            type = AimEngine.PocketType.CORNER
        ))
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                br.x - (br.x - bl.x) * cornerRatio * 0.5,
                br.y - (br.y - tr.y) * cornerRatio * 0.5
            ),
            radius = 40.0,
            type = AimEngine.PocketType.CORNER
        ))
        
        // 边袋（长边中点附近）
        val sideRatio = 0.48
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                tl.x + (tr.x - tl.x) * sideRatio,
                tl.y - (tl.y - bl.y) * 0.03  // 略偏外
            ),
            radius = 35.0,
            type = AimEngine.PocketType.SIDE
        ))
        pockets.add(AimEngine.Pocket(
            pos = AimEngine.Vec2(
                bl.x + (br.x - bl.x) * (1 - sideRatio),
                bl.y + (tl.y - bl.y) * 0.03
            ),
            radius = 35.0,
            type = AimEngine.PocketType.SIDE
        ))
        
        return pockets
    }
    
    // ========== 辅助 ==========
    
    private fun buildFromCorners(corners: Array<AimEngine.Vec2>): DetectedTable {
        val tw = corners[1].dist(corners[0])
        val th = corners[3].dist(corners[0])
        val pockets = locatePockets(corners, tw.toInt(), th.toInt())
        return DetectedTable(
            corners = corners,
            pockets = pockets,
            width = tw,
            height = th,
            rotation = 0.0,
            perspectiveCorrected = false,
            confidence = 1.0
        )
    }
    
    private fun buildDefault(w: Int, h: Int): DetectedTable {
        val margin = 0.05
        val corners = arrayOf(
            AimEngine.Vec2(w*margin, h*margin),
            AimEngine.Vec2(w*(1-margin), h*margin),
            AimEngine.Vec2(w*(1-margin), h*(1-margin)),
            AimEngine.Vec2(w*margin, h*(1-margin))
        )
        return buildFromCorners(corners)
    }
    
    private fun computeTableConfidence(
        corners: Array<AimEngine.Vec2>, lines: List<Pair<Double, Double>>
    ): Double {
        // 基于四边形规则度和直线数量评估置信度
        var score = 0.0
        
        // 角度接近90度加分
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i+1)%4]
            val c = corners[(i+2)%4]
            val ab = b.sub(a).normalize()
            val bc = c.sub(b).normalize()
            val angle = acos(max(-1.0, min(1.0, ab.dot(bc))))
            val rightness = abs(angle - PI/2) / (PI/2)
            score += 1.0 - min(1.0, rightness)
        }
        score /= 4.0
        
        // 直线数量加分
        val lineBonus = min(1.0, lines.size / 10.0) * 0.3
        score = score * 0.7 + lineBonus
        
        return min(1.0, score)
    }
    
    // ========== 袋口检测 ==========
    
    /**
     * 判断球是否落入袋口
     */
    fun isBallInPocket(
        ballPos: AimEngine.Vec2,
        pockets: List<AimEngine.Pocket>,
        ballRadius: Double
    ): AimEngine.Pocket? {
        for (p in pockets) {
            val d = ballPos.dist(p.pos)
            if (d < p.radius + ballRadius * 0.5) {
                return p
            }
        }
        return null
    }
    
    /**
     * 获取袋口方向（用于瞄准计算）
     */
    fun getPocketDirection(
        from: AimEngine.Vec2, pocket: AimEngine.Pocket
    ): AimEngine.Vec2 {
        return pocket.pos.sub(from).normalize()
    }
    
    // ========== 物理参数 ==========
    
    /**
     * 根据检测到的球桌尺寸推断物理参数
     */
    fun inferPhysics(table: DetectedTable): AimEngine.TableGeometry {
        // 像素到毫米的转换
        val pixelToMm = 2540.0 / table.width  // 假设标准9ft桌
        
        val pocketRadius = (table.width * params.pocketToTableRatio) / pixelToMm
        
        return AimEngine.TableGeometry(
            topLeft = table.corners[0],
            bottomRight = table.corners[2],
            pockets = table.pockets,
            cushionHeight = 35.5,
            friction = 0.98,
            restitution = 0.793
        )
    }
}
