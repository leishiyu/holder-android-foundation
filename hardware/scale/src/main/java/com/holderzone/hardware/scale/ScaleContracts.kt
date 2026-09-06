package com.holderzone.hardware.scale

/**
 * 当前 SDK 支持的称重厂商。
 */
enum class ScaleVendor {
    JW,
    LY,
    SOHE,
}

/**
 * 当前驱动能力矩阵。
 */
data class ScaleCapabilities(
    val selectedVendor: ScaleVendor? = null,
    val readWeight: Boolean = false,
    val streamWeight: Boolean = false,
    val tare: Boolean = false,
    val zero: Boolean = false,
    val stableSignal: Boolean = false,
) {
    companion object {
        val NONE = ScaleCapabilities()
    }
}

/**
 * 称重模块生命周期状态。
 */
sealed interface ScaleState {
    data object Idle : ScaleState

    data class Starting(
        val config: ScaleConfig,
    ) : ScaleState

    data class Running(
        val vendor: ScaleVendor,
        val capabilities: ScaleCapabilities,
    ) : ScaleState

    data class Stopped(
        val lastVendor: ScaleVendor? = null,
    ) : ScaleState

    data class Error(
        val vendor: ScaleVendor?,
        val error: ScaleError,
    ) : ScaleState

    data object Closed : ScaleState
}

/**
 * 一次性事件流。
 */
sealed interface ScaleEvent {
    data class VendorSelected(val vendor: ScaleVendor) : ScaleEvent

    data class Connected(val vendor: ScaleVendor) : ScaleEvent

    data class Disconnected(val vendor: ScaleVendor) : ScaleEvent

    data class WeightUpdated(val reading: WeightReading) : ScaleEvent

    data class Info(val message: String) : ScaleEvent

    data class Error(val error: ScaleError) : ScaleEvent
}

/**
 * SDK 统一结果包装。
 */
sealed interface ScaleResult<out T> {
    data class Ok<T>(val value: T) : ScaleResult<T>

    data class Err(val error: ScaleError) : ScaleResult<Nothing>
}

/**
 * SDK 统一错误模型。
 */
sealed class ScaleError(
    open val code: String,
    override val message: String,
    open val vendor: ScaleVendor? = null,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause) {

    class Configuration(
        message: String,
        cause: Throwable? = null,
    ) : ScaleError(
        code = "CONFIGURATION",
        message = message,
        cause = cause,
    )

    class DeviceUnavailable(
        message: String,
        vendor: ScaleVendor? = null,
        cause: Throwable? = null,
    ) : ScaleError(
        code = "DEVICE_UNAVAILABLE",
        message = message,
        vendor = vendor,
        cause = cause,
    )

    class Unsupported(
        message: String,
        vendor: ScaleVendor? = null,
    ) : ScaleError(
        code = "UNSUPPORTED",
        message = message,
        vendor = vendor,
    )

    class Communication(
        message: String,
        vendor: ScaleVendor? = null,
        cause: Throwable? = null,
    ) : ScaleError(
        code = "COMMUNICATION",
        message = message,
        vendor = vendor,
        cause = cause,
    )

    class OperationFailed(
        message: String,
        vendor: ScaleVendor? = null,
        cause: Throwable? = null,
    ) : ScaleError(
        code = "OPERATION_FAILED",
        message = message,
        vendor = vendor,
        cause = cause,
    )

    class Closed : ScaleError(
        code = "CLOSED",
        message = "ScaleFacade is already closed.",
    )
}

/**
 * 一次重量读数。
 */
data class WeightReading(
    val grams: Double,
    val tareGrams: Double? = null,
    val stable: Boolean? = null,
    val netMode: Boolean? = null,
    val zero: Boolean? = null,
    val rawFrame: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/**
 * SDK 唯一日志出口。
 */
interface ScaleLogger {
    fun debug(tag: String, message: String)

    fun info(tag: String, message: String)

    fun warn(tag: String, message: String)

    fun error(tag: String, message: String, throwable: Throwable? = null)

    data object None : ScaleLogger {
        override fun debug(tag: String, message: String) = Unit

        override fun info(tag: String, message: String) = Unit

        override fun warn(tag: String, message: String) = Unit

        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
