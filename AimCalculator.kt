package com.lingmiao.engine.aim

import kotlin.math.*
import com.lingmiao.engine.AimEngine

/**
 * 瞄准计算器 - 纯Kotlin几何运算库
 * 
 * 所有瞄准计算的核心数学，不依赖Android
 * 可在JVM测试环境直接运行
 */
object AimCalculator {
    
    // ========== 核心计算 ==========
    
    /**
     * 计算母球撞击目标球的精确瞄准方向
     * 
     * @param cuePos 母球位置
     * @param objPos 目标球位置
     * @param pocketPos 目标袋口位置
     * @param ballRadius 球半径
     * @return 母球应瞄准的方向向量（单位向量）
     */
    fun calcAimDirection(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        pocketPos: AimEngine.Vec2,
        ballRadius: Double = 25.4
    ): AimEngine.Vec2 {
        // 目标球中心到袋口方向
        val toPocket = pocketPos.sub(objPos).normalize()
        
        // 瞄准点 = 目标球中心 - 球半径 * 方向
        val aimPoint = objPos.sub(toPocket.mul(ballRadius))
        
        // 母球到瞄准点的方向
        val direction = aimPoint.sub(cuePos).normalize()
        
        return direction
    }
    
    /**
     * 计算假想球(Ghost Ball)位置
     * 假想球是目标球沿入袋方向的镜像位置
     * 母球瞄准假想球中心即为完美撞击
     */
    fun calcGhostBall(
        objPos: AimEngine.Vec2,
        pocketPos: AimEngine.Vec2,
        ballRadius: Double = 25.4
    ): AimEngine.Vec2 {
        val toPocket = pocketPos.sub(objPos).normalize()
        // 假想球在目标球后方（远离袋口方向）一个球直径处
        return objPos.sub(toPocket.mul(ballRadius * 2))
    }
    
    /**
     * 计算切球角度
     * 母球-目标球连线 与 目标球-袋口连线 的夹角
     */
    fun calcCutAngle(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        pocketPos: AimEngine.Vec2
    ): Double {
        val cueToObj = objPos.sub(cuePos).normalize()
        val objToPocket = pocketPos.sub(objPos).normalize()
        val dot = cueToObj.dot(objToPocket)
        val angle = acos(max(-1.0, min(1.0, dot)))
        return angle * 180.0 / PI
    }
    
    /**
     * 计算自然角(Natural Angle)
     * 即母球、目标球、袋口三点形成的角度
     * 越接近90°越难，越接近0°越容易
     */
    fun calcNaturalAngle(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        pocketPos: AimEngine.Vec2
    ): Double {
        val a = cuePos
        val b = objPos
        val c = pocketPos
        
        val ab = b.sub(a).normalize()
        val cb = b.sub(c).normalize()
        
        val dot = ab.dot(cb)
        val angle = acos(max(-1.0, min(1.0, dot)))
        return angle * 180.0 / PI
    }
    
    /**
     * 镜像反射 - 计算球撞击库边后的反弹方向
     * 
     * @param incomingDir 入射方向
     * @param cushionNormal 库边法线
     * @return 反射方向
     */
    fun mirrorReflect(
        incomingDir: AimEngine.Vec2,
        cushionNormal: AimEngine.Vec2
    ): AimEngine.Vec2 {
        val dot = incomingDir.dot(cushionNormal)
        return incomingDir.sub(cushionNormal.mul(2 * dot))
    }
    
    /**
     * 角度补偿 - 模拟真实物理的反弹偏移
     * 
     * @param mirrorDir 镜像反射方向
     * @param incidentAngle 入射角(度)
     * @param compensationRatio 补偿比例 (0.15~0.25)
     * @return 补偿后的方向
     */
    fun angleCompensate(
        mirrorDir: AimEngine.Vec2,
        incidentAngle: Double,
        compensationRatio: Double
    ): AimEngine.Vec2 {
        // 补偿量随入射角增大而增大
        val angleRad = incidentAngle * PI / 180.0
        val compAngle = compensationRatio * sin(angleRad)
        
        // 对方向做小角度旋转
        val c = cos(compAngle)
        val s = sin(compAngle)
        return AimEngine.Vec2(
            mirrorDir.x * c - mirrorDir.y * s,
            mirrorDir.x * s + mirrorDir.y * c
        )
    }
    
    /**
     * 混合模式 - 镜像和补偿的加权平均
     */
    fun hybridAim(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        pocketPos: AimEngine.Vec2,
        cushionNormal: AimEngine.Vec2,
        compensationRatio: Double,
        mirrorWeight: Double = 0.6
    ): AimEngine.Vec2 {
        val incidentDir = objPos.sub(cuePos).normalize()
        val incidentAngle = calcCutAngle(cuePos, objPos, pocketPos)
        
        val mirror = mirrorReflect(incidentDir, cushionNormal)
        val compensated = angleCompensate(mirror, incidentAngle, compensationRatio)
        
        // 加权平均
        return mirror.mul(mirrorWeight).add(compensated.mul(1.0 - mirrorWeight)).normalize()
    }
    
    /**
     * BFS搜索多库翻袋路线
     * 
     * @param cuePos 母球位置
     * @param objPos 目标球位置
     * @param pockets 所有袋口
     * @param tableRect 球桌矩形 (left, top, right, bottom)
     * @param maxBounces 最大反弹次数
     * @return 所有可行路线（按评分排序）
     */
    fun searchBankRoutes(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        pockets: List<AimEngine.Pocket>,
        tableRect: Array<AimEngine.Vec2>,  // [TL, TR, BR, BL]
        maxBounces: Int = 3,
        ballRadius: Double = 25.4
    ): List<BankRoute> {
        val routes = mutableListOf<BankRoute>()
        
        for (pocket in pockets) {
            // 生成镜像目标点
            val mirrorTargets = generateMirrorTargets(pocket.pos, tableRect, maxBounces)
            
            for ((bounces, target) in mirrorTargets) {
                // 从母球到镜像目标的直线
                val dir = target.sub(cuePos)
                if (dir.length() < 1e-6) continue
                
                // 找与库边的交点序列
                val bouncePoints = findBounceSequence(
                    cuePos, target, tableRect, bounces
                )
                
                if (bouncePoints.size != bounces) continue
                
                // 验证路线可行性
                if (!validateRoute(cuePos, objPos, bouncePoints, pocket, tableRect, ballRadius)) {
                    continue
                }
                
                // 计算路线评分
                val score = scoreRoute(cuePos, bouncePoints, pocket, bounces)
                
                routes.add(BankRoute(
                    bounces = bounces,
                    viaPoints = listOf(cuePos) + bouncePoints + listOf(objPos, pocket.pos),
                    pocket = pocket,
                    score = score
                ))
            }
        }
        
        return routes.sortedByDescending { it.score }
    }
    
    data class BankRoute(
        val bounces: Int,
        val viaPoints: List<AimEngine.Vec2>,
        val pocket: AimEngine.Pocket,
        val score: Double
    )
    
    /**
     * 生成镜像目标点
     */
    private fun generateMirrorTargets(
        target: AimEngine.Vec2,
        tableRect: Array<AimEngine.Vec2>,
        maxBounces: Int
    ): List<Pair<Int, AimEngine.Vec2>> {
        val results = mutableListOf<Pair<Int, AimEngine.Vec2>>()
        val tl = tableRect[0]; val tr = tableRect[1]
        val br = tableRect[2]; val bl = tableRect[3]
        
        // 1库镜像
        if (maxBounces >= 1) {
            // 上边镜像
            results.add(1 to AimEngine.Vec2(target.x, 2*tl.y - target.y))
            // 下边镜像
            results.add(1 to AimEngine.Vec2(target.x, 2*br.y - target.y))
            // 左边镜像
            results.add(1 to AimEngine.Vec2(2*tl.x - target.x, target.y))
            // 右边镜像
            results.add(1 to AimEngine.Vec2(2*tr.x - target.x, target.y))
        }
        
        // 2库镜像（简化的常见组合）
        if (maxBounces >= 2) {
            // 左上角镜像
            results.add(2 to AimEngine.Vec2(2*tl.x - target.x, 2*tl.y - target.y))
            // 右上角
            results.add(2 to AimEngine.Vec2(2*tr.x - target.x, 2*tr.y - target.y))
            // 左下角
            results.add(2 to AimEngine.Vec2(2*bl.x - target.x, 2*bl.y - target.y))
            // 右下角
            results.add(2 to AimEngine.Vec2(2*br.x - target.x, 2*br.y - target.y))
        }
        
        // 3库（仅关键位置）
        if (maxBounces >= 3) {
            val midTop = AimEngine.Vec2((tl.x+tr.x)/2, tl.y)
            val midBot = AimEngine.Vec2((bl.x+br.x)/2, br.y)
            val midLeft = AimEngine.Vec2(tl.x, (tl.y+bl.y)/2)
            val midRight = AimEngine.Vec2(tr.x, (tr.y+br.y)/2)
            
            results.add(3 to AimEngine.Vec2(target.x, 2*midTop.y - target.y))
            results.add(3 to AimEngine.Vec2(target.x, 2*midBot.y - target.y))
            results.add(3 to AimEngine.Vec2(2*midLeft.x - target.x, target.y))
            results.add(3 to AimEngine.Vec2(2*midRight.x - target.x, target.y))
        }
        
        return results
    }
    
    /**
     * 找从start到target的直线与库边的交点序列
     */
    private fun findBounceSequence(
        start: AimEngine.Vec2,
        target: AimEngine.Vec2,
        tableRect: Array<AimEngine.Vec2>,
        expectedBounces: Int
    ): List<AimEngine.Vec2> {
        val points = mutableListOf<AimEngine.Vec2>()
        val tl = tableRect[0]; val tr = tableRect[1]
        val br = tableRect[2]; val bl = tableRect[3]
        
        // 四条边
        val edges = listOf(
            Pair(tl, tr),  // 上
            Pair(tr, br),  // 右
            Pair(br, bl),  // 下
            Pair(bl, tl),  // 左
        )
        
        // 射线-线段相交
        var currentStart = start
        var currentTarget = target
        val visitedEdges = mutableSetOf<Int>()
        
        for (bounce in 0 until expectedBounces) {
            var bestT = Double.POSITIVE_INFINITY
            var bestPoint: AimEngine.Vec2? = null
            var bestEdgeIdx = -1
            
            for (i in edges.indices) {
                if (i in visitedEdges) continue
                val edge = edges[i]
                val hit = raySegmentIntersect(
                    currentStart, currentTarget, edge.first, edge.second
                )
                if (hit != null) {
                    val t = hit.dist(currentStart)
                    if (t < bestT) {
                        bestT = t
                        bestPoint = hit
                        bestEdgeIdx = i
                    }
                }
            }
            
            if (bestPoint == null) break
            points.add(bestPoint)
            visitedEdges.add(bestEdgeIdx)
            
            // 反射方向
            val edgeNormal = when (bestEdgeIdx) {
                0 -> AimEngine.Vec2(0.0, -1.0)  // 上边，法线向下
                1 -> AimEngine.Vec2(-1.0, 0.0)  // 右边，法线向左
                2 -> AimEngine.Vec2(0.0, 1.0)   // 下边，法线向上
                3 -> AimEngine.Vec2(1.0, 0.0)   // 左边，法线向右
                else -> AimEngine.Vec2(0.0, 0.0)
            }
            
            val incoming = currentTarget.sub(currentStart).normalize()
            val reflected = mirrorReflect(incoming, edgeNormal)
            
            currentStart = bestPoint
            currentTarget = bestPoint.add(reflected.mul(10000.0))
        }
        
        return points
    }
    
    private fun raySegmentIntersect(
        rayStart: AimEngine.Vec2,
        rayEnd: AimEngine.Vec2,
        segA: AimEngine.Vec2,
        segB: AimEngine.Vec2
    ): AimEngine.Vec2? {
        val r = rayEnd.sub(rayStart)
        val s = segB.sub(segA)
        
        val denom = r.cross(s)
        if (abs(denom) < 1e-10) return null
        
        val d = segA.sub(rayStart)
        val t = d.cross(s) / denom
        val u = d.cross(r) / denom
        
        if (t > 1e-6 && t < 1.0 && u >= 0.0 && u <= 1.0) {
            return rayStart.add(r.mul(t))
        }
        return null
    }
    
    /**
     * 验证路线可行性
     */
    private fun validateRoute(
        cuePos: AimEngine.Vec2,
        objPos: AimEngine.Vec2,
        bouncePoints: List<AimEngine.Vec2>,
        pocket: AimEngine.Pocket,
        tableRect: Array<AimEngine.Vec2>,
        ballRadius: Double
    ): Boolean {
        // 检查反弹点是否在库边上（不在袋口区域）
        for (bp in bouncePoints) {
            // 检查是否太靠近袋口
            for (p in listOf(pocket)) {
                if (bp.dist(p.pos) < p.radius * 2) return false
            }
        }
        
        // 检查母球到第一个反弹点的路线是否畅通
        if (bouncePoints.isNotEmpty()) {
            val firstBounce = bouncePoints.first()
            val dist = cuePos.dist(firstBounce)
            if (dist < ballRadius * 3) return false  // 太近
        }
        
        return true
    }
    
    /**
     * 路线评分
     */
    private fun scoreRoute(
        cuePos: AimEngine.Vec2,
        bouncePoints: List<AimEngine.Vec2>,
        pocket: AimEngine.Pocket,
        bounces: Int
    ): Double {
        var score = 1.0
        
        // 距离惩罚
        var totalDist = 0.0
        var prev = cuePos
        for (p in bouncePoints) {
            totalDist += prev.dist(p)
            prev = p
        }
        totalDist += prev.dist(pocket.pos)
        score *= (1.0 - min(0.5, totalDist / 10000.0))
        
        // 反弹次数惩罚
        score *= pow(0.7, bounces.toDouble())
        
        // 袋口大小加成（角袋比边袋大）
        val pocketBonus = when (pocket.type) {
            AimEngine.PocketType.CORNER -> 1.1
            AimEngine.PocketType.SIDE -> 0.95
            else -> 1.0
        }
        score *= pocketBonus
        
        return max(0.01, score)
    }
    
    // ========== 袋口判定 ==========
    
    /**
     * 判断目标球被撞后是否入袋
     */
    fun willBallPot(
        objPos: AimEngine.Vec2,
        impactDir: AimEngine.Vec2,  // 目标球被撞后的运动方向
        pocket: AimEngine.Pocket,
        ballRadius: Double = 25.4,
        margin: Double = 0.8  // 入袋余量
    ): Boolean {
        // 目标球中心到袋口中心的方向
        val toPocket = pocket.pos.sub(objPos).normalize()
        val travelDist = objPos.dist(pocket.pos)
        
        // 球中心需要到达袋口中心附近
        // 考虑球半径，球中心到达袋口中心-pocketRadius+ballRadius时算入袋
        val effectiveRadius = pocket.radius - ballRadius * margin
        
        // 检查方向是否正确（朝向袋口）
        val dirAlignment = impactDir.normalize().dot(toPocket)
        if (dirAlignment < 0.5) return false  // 方向偏差太大
        
        // 检查距离是否在合理范围
        if (travelDist > effectiveRadius + ballRadius * 3) return false
        
        return true
    }
    
    // ========== 力度计算 ==========
    
    /**
     * 计算将球打入袋口所需力度
     * 
     * @param dist 距离(mm)
     * @param friction 摩擦系数
     * @param bounces 反弹次数
     * @return 归一化力度 0~1
     */
    fun calcRequiredForce(
        dist: Double,
        friction: Double = 0.98,
        bounces: Int = 0
    ): Double {
        // 能量衰减: v_final = v_init * friction^dist
        // 需要 v_final > 0 (球到达袋口还有速度)
        // 简化: force ∝ dist / (friction^dist)
        val decay = pow(friction, dist / 1000.0)
        var force = (1.0 - decay) * (dist / 2000.0)
        
        // 反弹损失
        force *= (1.0 + bounces * 0.25)
        
        return min(1.0, force)
    }
}
