package com.lingmiao.core.event

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 灵喵事件总线
 * 
 * 基于Kotlin Flow的响应式事件系统
 * 支持:
 * - 粘性事件 (Sticky)
 * - 事件缓冲
 * - 生命周期感知
 */
object EventBus {
    
    // ========== 事件定义 ==========
    
    sealed class AppEvent {
        // 权限相关
        data class PermissionGranted(val type: String) : AppEvent()
        data class PermissionDenied(val type: String) : AppEvent()
        
        // 服务相关
        object ServiceStarted : AppEvent()
        object ServiceStopped : AppEvent()
        
        // 校准相关
        data class CalibrationCompleted(val confidence: Double) : AppEvent()
        data class CalibrationFailed(val reason: String) : AppEvent()
        
        // 检测相关
        data class BallsDetected(val count: Int, val fps: Float) : AppEvent()
        data class NoBallsDetected(val reason: String) : AppEvent()
        
        // 瞄准相关
        data class AimComputed(val aimCount: Int, val bestScore: Double) : AppEvent()
        data class AimModeChanged(val mode: String) : AppEvent()
        
        // 配置相关
        data class ConfigChanged(val key: String, val value: Any) : AppEvent()
        
        // 错误相关
        data class Error(val code: Int, val message: String, val fatal: Boolean) : AppEvent()
        
        // 生命周期
        object AppPaused : AppEvent()
        object AppResumed : AppEvent()
        object AppDestroyed : AppEvent()
    }
    
    // ========== 实现 ==========
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()
    
    // 粘性事件存储
    private val stickyEvents = ConcurrentHashMap<String, AppEvent>()
    
    fun emit(event: AppEvent) {
        _events.tryEmit(event)
        
        // 粘性事件
        when (event) {
            is AppEvent.CalibrationCompleted -> stickyEvents["calibration"] = event
            is AppEvent.ServiceStarted -> stickyEvents["service"] = event
            is AppEvent.ConfigChanged -> stickyEvents["config_${event.key}"] = event
            else -> {}
        }
    }
    
    fun getSticky(key: String): AppEvent? = stickyEvents[key]
    
    fun clearSticky(key: String) {
        stickyEvents.remove(key)
    }
    
    // ========== 订阅辅助 ==========
    
    fun subscribe(
        scope: CoroutineScope,
        onEvent: (AppEvent) -> Unit
    ): Job {
        return events.onEach { onEvent(it) }
            .catch { e -> android.util.Log.e("EventBus", "Error: ${e.message}") }
            .launchIn(scope)
    }
    
    fun <T : AppEvent> subscribe(
        scope: CoroutineScope,
        eventClass: Class<T>,
        onEvent: (T) -> Unit
    ): Job {
        return events.filterIsInstance(eventClass)
            .onEach { onEvent(it) }
            .launchIn(scope)
    }
    
    fun shutdown() {
        scope.cancel()
        stickyEvents.clear()
    }
}
