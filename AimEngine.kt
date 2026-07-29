package com.lingmiao.engine

import kotlin.math.*

/**
 * 灵喵 LingMiao - 瞄准引擎核心
 * 
 * 自动判定母球(Cue Ball)与目标球(Object Ball)及袋口(Pocket)之间的最优路线
 * 
 * 算法流程:
 * 1. 球检测 → 识别母球/目标球/袋口位置
 * 2. 碰撞几何 → 计算母球撞击目标球的精确瞄准点
 * 3. 路线搜索 → 直接入袋 / 一库翻袋 / 多库翻袋 / 借球
 * 4. 物理模拟 → 摩擦衰减 + 碰撞弹性 + 旋转效应
 * 5. 评分排序 → 成功率/难度/走位综合评分
 */
class AimEngine(private val config: AimConfig) {
    
    // ========== 数据结构 ==========
    data class Vec2(val x: Double, val y: Double) {
        fun length(): Double = sqrt(x*x + y*y)
        fun normalize(): Vec2 {
            val l = length()
            return if (l < 1e-9) Vec2(0.0, 0.0) else Vec2(x/l, y/l)
        }
        fun dot(o: Vec2): Double = x*o.x + y*o.y
        fun cross(o: Vec2): Double = x*o.y - y*o.x
        fun add(o: Vec2): Vec2 = Vec2(x+o.x, y+o.y)
        fun sub(o: Vec2): Vec2 = Vec2(x-o.x, y-o.y)
        fun mul(s: Double): Vec2 = Vec2(x*s, y*s)
        fun dist(o: Vec2): Double = sub(o).length()
        fun angle(): Double = atan2(y, x)
        fun rotate(rad: Double): Vec2 {
            val c = cos(rad); val s = sin(rad)
            return Vec2(x*c - y*s, x*s + y*c)
        }
    }
    
    data class Ball(
        val pos: Vec2,
        val radius: Double = 25.4, // mm
        val type: BallType = BallType.UNKNOWN,
        val color: Int = 0xFFFFFFFF.toInt()
    )
    
    enum class BallType { CUE, OBJECT, UNKNOWN }
    
    data class Pocket(
        val pos: Vec2,
        val radius: Double = 57.15,
        val type: PocketType
    )
    
    enum class PocketType { CORNER, SIDE, HEAD, FOOT }
    
    data class TableGeometry(
        val topLeft: Vec2,
        val bottomRight: Vec2,
        val pockets: List<Pocket>,
        val cushionHeight: Double = 35.5,
        val friction: Double = 0.98,
        val restitution: Double = 0.793
    )
    
    data class AimConfig(
        val compensationRatio: Double = 0.18,
        val maxBounces: Int = 3,
        val lineWidth: Float = 5.0f,
        val lineColor: Int = 0xFF00FF00.toInt(),
        val showAntLine: Boolean = false,
        val snapNearestBall: Boolean = true,
        val scheme: RecognitionScheme = RecognitionScheme.PRECISE
    )
    
    enum class RecognitionScheme { PRECISE, FAST, LOW_LIGHT, HIGH_CONTRAST }
    
    // ========== 瞄准方案 ==========
    enum class AimMode { MIRROR_REFLECTION, ANGLE_COMPENSATION, HYBRID }
    
    data class AimLine(
        val start: Vec2,        // 母球位置
        val hitPoint: Vec2,     // 母球撞击目标球点
        val targetPoint: Vec2,  // 目标球入袋点
        val bounces: Int,       // 反弹次数
        val viaPoints: List<Vec2>, // 路线经过的点（含库边反弹点）
        val mode: AimMode,
        val successRate: Double, // 预估成功率 0~1
        val difficulty: Double,  // 难度 0~1
        val cutAngle: Double,    // 切球角度(度)
        val isBankShot: Boolean, // 是否翻袋
        val isComboShot: Boolean,// 是否借球
        val score: Double       // 综合评分
    )
    
    // ========== 核心算法 ==========
    
    /**
     * 主入口: 自动判定最优路线
     * 
     * @param cueBall 母球位置
     * @param balls 所有检测到的球
     * @param table 球桌几何
     * @param mode 瞄准模式
     * @return 排序后的瞄准方案列表（最优在前）
     */
    fun findBestAim(
        cueBall: Ball,
        balls: List<Ball>,
        table: TableGeometry,
        mode: AimMode = AimMode.HYBRID
    ): List<AimLine> {
        val objectBalls = balls.filter { it.type == BallType.OBJECT }
        if (objectBalls.isEmpty()) return emptyList()
        
        val allAims = mutableListOf<AimLine>()
        
        // 对每颗目标球，搜索最优路线
        for (objBall in objectBalls) {
            // 1. 直接入袋路线
            allAims.addAll(findDirectPockets(cueBall, objBall, table, mode))
            
            // 2. 一库翻袋
            if (config.maxBounces >= 1) {
                allAims.addAll(findBankShots(cueBall, objBall, table, 1, mode))
            }
            
            // 3. 多库翻袋 (2~N库)
            for (b in 2..config.maxBounces) {
                allAims.addAll(findBankShots(cueBall, objBall, table, b, mode))
            }
            
            // 4. 借球（Kick shot）
            allAims.addAll(findKickShots(cueBall, objBall, balls, table, mode))
        }
        
        // 按综合评分排序
        return allAims.sortedByDescending { it.score }
    }
    
    // ========== 1. 直接入袋 ==========
    private fun findDirectPockets(
        cue: Ball, obj: Ball, table: TableGeometry, mode: AimMode
    ): List<AimLine> {
        val results = mutableListOf<AimLine>()
        
        for (pocket in table.pockets) {
            // 检查从obj到pocket的路线上是否有其他球阻挡
            if (isPathBlocked(obj.pos, pocket.pos, listOf(cue) + getOtherBalls(cue, obj), table)) {
                continue
            }
            
            // 计算瞄准点: 母球需要把目标球撞向袋口
            // 目标球中心到袋口方向
            val toPocket = pocket.pos.sub(obj.pos).normalize()
            // 瞄准点 = 目标球中心 + (球半径方向指向袋口)
            val aimDir = toPocket.mul(obj.radius)
            
            // 母球需要站在的位置: 瞄准点反方向
            val cueToObj = obj.pos.sub(aimDir).sub(cue.pos)
            val dist = cueToObj.length()
            
            // 切球角度
            val cutAngle = computeCutAngle(cue.pos, obj.pos, pocket.pos)
            
            // 成功率评估
            val successRate = estimateSuccessRate(
                cue.pos, obj.pos, pocket.pos, cutAngle, 0, table
            )
            
            // 难度
            val difficulty = computeDifficulty(cutAngle, dist, 0)
            
            // 综合评分
            val score = computeScore(successRate, difficulty, dist, cutAngle)
            
            results.add(AimLine(
                start = cue.pos,
                hitPoint = obj.pos.sub(aimDir),
                targetPoint = pocket.pos,
                bounces = 0,
                viaPoints = listOf(cue.pos, obj.pos, pocket.pos),
                mode = mode,
                successRate = successRate,
                difficulty = difficulty,
                cutAngle = cutAngle,
                isBankShot = false,
                isComboShot = false,
                score = score
            ))
        }
        
        return results
    }
    
    // ========== 2. 翻袋路线搜索 ==========
    private fun findBankShots(
        cue: Ball, obj: Ball, table: TableGeometry, bounces: Int, mode: AimMode
    ): List<AimLine> {
        val results = mutableListOf<AimLine>()
        
        // 对每颗袋口，用镜像法搜索bounces库路线
        for (pocket in table.pockets) {
            // 镜像法: 将目标点（袋口）对库边做bounces次镜像
            // 然后找从母球到镜像点的直线，与库边的交点即为反弹点
            val mirrorPoints = generateMirrorPoints(pocket.pos, table, bounces)
            
            for (mirrorTarget in mirrorPoints) {
                // 母球到镜像目标的直线
                val dir = mirrorTarget.sub(cue.pos)
                if (dir.length() < 1e-6) continue
                
                // 找与库边的交点（反弹点）
                val bouncePoints = findBouncePoints(cue.pos, mirrorTarget, table, bounces)
                if (bouncePoints.size != bounces) continue
                
                // 验证: 从最后一个反弹点到袋口的路线是否畅通
                val lastBounce = bouncePoints.last()
                if (isPathBlocked(lastBounce, obj.pos, listOf(cue, obj), table)) continue
                if (isPathBlocked(obj.pos, pocket.pos, listOf(cue, obj), table)) continue
                
                // 验证: 目标球被撞后确实朝袋口方向走
                val postImpact = computePostImpactDirection(obj.pos, lastBounce, pocket.pos)
                if (postImpact.dot(pocket.pos.sub(obj.pos).normalize()) < 0.3) continue
                
                // 计算实际路线点序列
                val viaPoints = mutableListOf(cue.pos)
                viaPoints.addAll(bouncePoints)
                viaPoints.add(obj.pos)
                viaPoints.add(pocket.pos)
                
                // 切球角度
                val cutAngle = computeCutAngle(cue.pos, obj.pos, pocket.pos)
                
                // 成功率（翻袋成功率递减）
                val baseRate = estimateSuccessRate(cue.pos, obj.pos, pocket.pos, cutAngle, bounces, table)
                val bankPenalty = pow(0.7, bounces.toDouble())
                val successRate = baseRate * bankPenalty
                
                val difficulty = computeDifficulty(cutAngle, cue.pos.dist(pocket.pos), bounces)
                val score = computeScore(successRate, difficulty, cue.pos.dist(pocket.pos), cutAngle) * bankPenalty
                
                results.add(AimLine(
                    start = cue.pos,
                    hitPoint = obj.pos,
                    targetPoint = pocket.pos,
                    bounces = bounces,
                    viaPoints = viaPoints,
                    mode = mode,
                    successRate = successRate,
                    difficulty = difficulty,
                    cutAngle = cutAngle,
                    isBankShot = true,
                    isComboShot = false,
                    score = score
                ))
            }
        }
        
        return results
    }
    
    // ========== 3. 借球 (Kick shot) ==========
    private fun findKickShots(
        cue: Ball, obj: Ball, allBalls: List<Ball>, table: TableGeometry, mode: AimMode
    ): List<AimLine> {
        val results = mutableListOf<AimLine>()
        val otherBalls = getOtherBalls(cue, obj, allBalls)
        
        // 找一颗中间球作为跳板
        for (midBall in otherBalls) {
            if (midBall.type != BallType.OBJECT) continue
            
            // 母球 → 中间球 → 目标球 → 袋口
            for (pocket in table.pockets) {
                // 检查中间球到目标球的路线
                if (isPathBlocked(midBall.pos, obj.pos, listOf(cue, obj, midBall), table)) continue
                if (isPathBlocked(obj.pos, pocket.pos, listOf(cue, obj, midBall), table)) continue
                
                // 母球到中间球的路线
                if (isPathBlocked(cue.pos, midBall.pos, listOf(cue, obj, midBall), table)) continue
                
                // 计算母球撞击中间球后，中间球能否撞到目标球
                val midToObj = obj.pos.sub(midBall.pos).normalize()
                val cueToMid = midBall.pos.sub(cue.pos).normalize()
                
                // 检查撞击后中间球方向正确
                val dotProduct = midToObj.dot(cueToMid)
                if (dotProduct < 0.3) continue  // 角度太大，不可行
                
                val viaPoints = listOf(cue.pos, midBall.pos, obj.pos, pocket.pos)
                val cutAngle = computeCutAngle(cue.pos, midBall.pos, obj.pos)
                val dist = cue.pos.dist(pocket.pos)
                
                // 借球成功率较低
                val successRate = 0.25 * max(0.0, dotProduct)
                val difficulty = computeDifficulty(cutAngle, dist, 1)
                val score = computeScore(successRate, difficulty, dist, cutAngle) * 0.4
                
                results.add(AimLine(
                    start = cue.pos,
                    hitPoint = midBall.pos,
                    targetPoint = pocket.pos,
                    bounces = 0,
                    viaPoints = viaPoints,
                    mode = mode,
                    successRate = successRate,
                    difficulty = difficulty,
                    cutAngle = cutAngle,
                    isBankShot = false,
                    isComboShot = true,
                    score = score
                ))
            }
        }
        
        return results
    }
    
    // ========== 镜像反射算法 ==========
    
    /**
     * 对库边做镜像反射
     * 袋口位置对每条库边做镜像，生成虚拟目标点
     */
    private fun generateMirrorPoints(
        target: Vec2, table: TableGeometry, bounces: Int
    ): List<Vec2> {
        val results = mutableListOf<Vec2>()
        val tl = table.topLeft
        val br = table.bottomRight
        
        // 四条边
        val edges = listOf(
            // 上边 (y = tl.y)
            Pair(0, { p: Vec2 -> Vec2(p.x, 2*tl.y - p.y) }),
            // 下边 (y = br.y)
            Pair(1, { p: Vec2 -> Vec2(p.x, 2*br.y - p.y) }),
            // 左边 (x = tl.x)
            Pair(2, { p: Vec2 -> Vec2(2*tl.x - p.x, p.y) }),
            // 右边 (x = br.x)
            Pair(3, { p: Vec2 -> Vec2(2*br.x - p.x, p.y) }),
        )
        
        if (bounces == 1) {
            // 一次镜像: 直接对每条边反射
            for (_, reflect in edges) {
                results.add(reflect(target))
            }
        } else {
            // 多次镜像: 递归反射
            val firstMirror = edges.map { it.second(target) }
            for (m in firstMirror) {
                // 第二次镜像（不能反射回原边）
                for (_, reflect in edges) {
                    val m2 = reflect(m)
                    results.add(m2)
                }
            }
            // 更多次... (简化为2次)
        }
        
        return results
    }
    
    /**
     * 找从start到mirrorTarget的直线与库边的交点
     */
    private fun findBouncePoints(
        start: Vec2, mirrorTarget: Vec2, table: TableGeometry, bounces: Int
    ): List<Vec2> {
        val points = mutableListOf<Vec2>()
        val tl = table.topLeft
        val br = table.bottomRight
        
        // 简化: 找直线与矩形四边的交点
        val intersections = mutableListOf<Vec2>()
        
        // 上边 y = tl.y
        val t = (tl.y - start.y) / (mirrorTarget.y - start.y)
        if (t > 0 && t < 1) {
            val ix = start.x + t * (mirrorTarget.x - start.x)
            if (ix >= tl.x && ix <= br.x) intersections.add(Vec2(ix, tl.y))
        }
        // 下边 y = br.y
        val tb = (br.y - start.y) / (mirrorTarget.y - start.y)
        if (tb > 0 && tb < 1) {
            val ix = start.x + tb * (mirrorTarget.x - start.x)
            if (ix >= tl.x && ix <= br.x) intersections.add(Vec2(ix, br.y))
        }
        // 左边 x = tl.x
        val tlX = (tl.x - start.x) / (mirrorTarget.x - start.x)
        if (tlX > 0 && tlX < 1) {
            val iy = start.y + tlX * (mirrorTarget.y - start.y)
            if (iy >= tl.y && iy <= br.y) intersections.add(Vec2(tl.x, iy))
        }
        // 右边 x = br.x
        val trX = (br.x - start.x) / (mirrorTarget.x - start.x)
        if (trX > 0 && trX < 1) {
            val iy = start.y + trX * (mirrorTarget.y - start.y)
            if (iy >= tl.y && iy <= br.y) intersections.add(Vec2(br.x, iy))
        }
        
        // 按距离排序
        intersections.sortBy { it.dist(start) }
        
        // 取前bounces个
        return intersections.take(bounces)
    }
    
    // ========== 几何工具 ==========
    
    /**
     * 检查从a到b的路线上是否有球阻挡
     */
    private fun isPathBlocked(
        a: Vec2, b: Vec2, obstacles: List<Ball>, table: TableGeometry
    ): Boolean {
        val dir = b.sub(a).normalize()
        val dist = a.dist(b)
        
        for (ball in obstacles) {
            if (ball.pos == a || ball.pos == b) continue
            // 球心到直线的距离
            val toBall = ball.pos.sub(a)
            val projLen = toBall.dot(dir)
            if (projLen < 0 || projLen > dist) continue
            val closest = a.add(dir.mul(projLen))
            val perpDist = ball.pos.dist(closest)
            // 考虑球的半径（两个球各占半径）
            if (perpDist < ball.radius * 2.1) return true  // 2.1x 留余量
        }
        
        // 检查是否出界（不考虑袋口区域）
        return false
    }
    
    /**
     * 计算切球角度（母球-目标球-袋口形成的角度）
     */
    private fun computeCutAngle(cuePos: Vec2, objPos: Vec2, pocketPos: Vec2): Double {
        val cueToObj = objPos.sub(cuePos).normalize()
        val objToPocket = pocketPos.sub(objPos).normalize()
        val dot = cueToObj.dot(objToPocket)
        val angle = acos(max(-1.0, min(1.0, dot)))
        return angle * 180.0 / PI
    }
    
    /**
     * 计算撞击后目标球的运动方向
     */
    private fun computePostImpactDirection(
        objPos: Vec2, bouncePoint: Vec2, pocketPos: Vec2
    ): Vec2 {
        // 反弹后的方向 = 从反弹点到目标球到袋口
        val fromBounce = objPos.sub(bouncePoint).normalize()
        val toPocket = pocketPos.sub(objPos).normalize()
        // 入射角 = 出射角
        // 简化: 返回反射方向
        return fromBounce.add(toPocket).normalize()
    }
    
    // ========== 评分系统 ==========
    
    /**
     * 预估成功率
     */
    private fun estimateSuccessRate(
        cuePos: Vec2, objPos: Vec2, pocketPos: Vec2,
        cutAngle: Double, bounces: Int, table: TableGeometry
    ): Double {
        var rate = 0.85  // 基础成功率
        
        // 切球角度惩罚（越薄越低）
        if (cutAngle > 60) rate *= 0.3
        else if (cutAngle > 45) rate *= 0.5
        else if (cutAngle > 30) rate *= 0.7
        else if (cutAngle > 15) rate *= 0.9
        
        // 距离惩罚
        val dist = cuePos.dist(objPos) + objPos.dist(pocketPos)
        val normDist = dist / 3000.0  // 归一化到球桌尺寸
        rate *= (1.0 - normDist * 0.2)
        
        // 翻袋惩罚
        rate *= pow(0.75, bounces.toDouble())
        
        // 角度补偿模式加成
        if (config.compensationRatio > 0) {
            rate *= (1.0 + config.compensationRatio * 0.1)
        }
        
        return max(0.02, min(0.98, rate))
    }
    
    /**
     * 计算难度
     */
    private fun computeDifficulty(cutAngle: Double, totalDist: Double, bounces: Int): Double {
        var diff = 0.0
        diff += min(cutAngle / 90.0, 1.0) * 0.4  // 切球角度
        diff += min(totalDist / 4000.0, 1.0) * 0.3  // 距离
        diff += min(bounces / 3.0, 1.0) * 0.3  // 翻袋
        return min(1.0, diff)
    }
    
    /**
     * 综合评分
     */
    private fun computeScore(
        successRate: Double, difficulty: Double, dist: Double, cutAngle: Double
    ): Double {
        // 权重
        val wSuccess = 0.5
        val wDifficulty = 0.2  // 越简单越好（低难度=高评分）
        val wDist = 0.15       // 越近越好
        val wCut = 0.15        // 切球角度适中最好
        
        val distScore = max(0.0, 1.0 - dist / 5000.0)
        val cutScore = 1.0 - min(cutAngle / 90.0, 1.0)
        
        return wSuccess * successRate +
               wDifficulty * (1.0 - difficulty) +
               wDist * distScore +
               wCut * cutScore
    }
    
    // ========== 辅助 ==========
    
    private fun getOtherBalls(vararg exclude: Ball): List<Ball> {
        // 从已知球列表中排除指定球
        // 实际实现中会从BallDetector获取
        return emptyList()
    }
    
    private fun getOtherBalls(
        cue: Ball, obj: Ball, allBalls: List<Ball>
    ): List<Ball> {
        return allBalls.filter { it != cue && it != obj }
    }
    
    // ========== 角度补偿模式 ==========
    
    /**
     * 角度补偿: 考虑球桌布摩擦、球速衰减等因素
     * 对瞄准方向做微调
     */
    fun applyCompensation(aim: AimLine, table: TableGeometry): AimLine {
        if (config.compensationRatio <= 0) return aim
        
        // 路线总长
        var totalDist = 0.0
        for (i in 0 until aim.viaPoints.size - 1) {
            totalDist += aim.viaPoints[i].dist(aim.viaPoints[i+1])
        }
        
        // 摩擦导致的速度衰减
        val speedDecay = pow(table.friction, totalDist / 1000.0)
        
        // 补偿量: 距离越远，补偿越大
        val compAngle = config.compensationRatio * (totalDist / 3000.0) * (PI / 180.0)
        
        // 旋转效应 (English/Side spin)
        // 左塞使球略微右偏，右塞使球略微左偏
        // 简化: 对方向做小角度旋转
        val lastSeg = aim.viaPoints.last().sub(aim.viaPoints[aim.viaPoints.size-2])
        val adjusted = lastSeg.rotate(compAngle)
        
        // 构建新的路线点
        val newPoints = aim.viaPoints.toMutableList()
        if (newPoints.size >= 2) {
            newPoints[newPoints.size-1] = newPoints[newPoints.size-2].add(adjusted)
        }
        
        return aim.copy(
            viaPoints = newPoints,
            successRate = min(0.98, aim.successRate * (0.9 + speedDecay * 0.1)),
            mode = AimMode.ANGLE_COMPENSATION
        )
    }
    
    // ========== 吸附最近球 ==========
    
    /**
     * 当检测到多颗球位置接近时，吸附到最近的球
     * 避免误判目标球
     */
    fun snapToNearestBall(rawPos: Vec2, balls: List<Ball>, threshold: Double = 30.0): Ball? {
        if (!config.snapNearestBall) return null
        var best: Ball? = null
        var bestDist = threshold
        for (ball in balls) {
            val d = rawPos.dist(ball.pos)
            if (d < bestDist) {
                bestDist = d
                best = ball
            }
        }
        return best
    }
    
    // ========== 渲染数据输出 ==========
    
    /**
     * 将瞄准线转换为渲染指令
     */
    fun toRenderData(aim: AimLine): RenderCommand {
        return RenderCommand(
            points = aim.viaPoints,
            color = config.lineColor,
            width = config.lineWidth,
            isDashed = config.showAntLine,
            bounces = aim.bounces,
            score = aim.score,
            successRate = aim.successRate,
            label = if (aim.isBankShot) "翻袋×${aim.bounces}" 
                    else if (aim.isComboShot) "借球" 
                    else "直接"
        )
    }
    
    data class RenderCommand(
        val points: List<Vec2>,
        val color: Int,
        val width: Float,
        val isDashed: Boolean,
        val bounces: Int,
        val score: Double,
        val successRate: Double,
        val label: String
    )
}
