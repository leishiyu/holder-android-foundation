package com.holderzone.hardware.scale.core

import android.content.Context
import android.os.Looper
import com.holderzone.hardware.scale.ScaleCapabilities
import com.holderzone.hardware.scale.ScaleConfig
import com.holderzone.hardware.scale.ScaleError
import com.holderzone.hardware.scale.ScaleEvent
import com.holderzone.hardware.scale.ScaleFacade
import com.holderzone.hardware.scale.ScalePortConfig
import com.holderzone.hardware.scale.ScaleResult
import com.holderzone.hardware.scale.ScaleState
import com.holderzone.hardware.scale.ScaleVendor
import com.holderzone.hardware.scale.ScaleVendorPreference
import com.holderzone.hardware.scale.WeightReading
import com.holderzone.hardware.scale.internal.ScalePortCatalog
import com.holderzone.hardware.scale.internal.spi.ScaleDriver
import com.holderzone.hardware.scale.internal.spi.ScaleDriverFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认称重 facade。
 *
 * 所有 lifecycle 命令和控制命令都通过单通道顺序执行，
 * 避免 start / stop / tare / zero / close 并发交错后把底层 driver 状态打乱。
 */
class DefaultScaleFacade(
    context: Context,
    private val driverFactories: List<ScaleDriverFactory>,
) : ScaleFacade {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandChannel = Channel<FacadeCommand>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    private val mutableState = MutableStateFlow<ScaleState>(ScaleState.Idle)
    private val mutableCapabilities = MutableStateFlow(ScaleCapabilities.NONE)
    private val mutableEvents = MutableSharedFlow<ScaleEvent>(extraBufferCapacity = 64)
    private val mutableReadings = MutableSharedFlow<WeightReading>(replay = 1, extraBufferCapacity = 16)

    private var currentConfig: ScaleConfig? = null
    private var activeDriver: ScaleDriver? = null
    private var activeFactory: ScaleDriverFactory? = null
    private var activeVendor: ScaleVendor? = null
    private var activePort: ScalePortConfig? = null
    private var bridgeJobs: List<Job> = emptyList()

    override val state: StateFlow<ScaleState> = mutableState.asStateFlow()
    override val events: Flow<ScaleEvent> = mutableEvents.asSharedFlow()
    override val capabilities: StateFlow<ScaleCapabilities> = mutableCapabilities.asStateFlow()
    override val readings: Flow<WeightReading> = mutableReadings.asSharedFlow()

    init {
        scope.launch {
            for (command in commandChannel) {
                when (command) {
                    is FacadeCommand.Start -> command.complete(handleStart(command.config))
                    is FacadeCommand.ReadOnce -> command.complete(handleReadOnce())
                    is FacadeCommand.Tare -> command.complete(handleTare())
                    is FacadeCommand.Zero -> command.complete(handleZero())
                    is FacadeCommand.Stop -> command.complete(handleStop())
                    is FacadeCommand.Close -> {
                        command.complete(handleCloseInternal())
                        break
                    }
                }
            }
        }
    }

    override suspend fun start(config: ScaleConfig): ScaleResult<Unit> {
        return submit(FacadeCommand.Start(config))
    }

    override suspend fun readOnce(): ScaleResult<WeightReading> {
        return submit(FacadeCommand.ReadOnce())
    }

    override suspend fun tare(): ScaleResult<Unit> {
        return submit(FacadeCommand.Tare())
    }

    override suspend fun zero(): ScaleResult<Unit> {
        return submit(FacadeCommand.Zero())
    }

    override suspend fun stop(): ScaleResult<Unit> {
        return submit(FacadeCommand.Stop())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        val deferred = CompletableDeferred<ScaleResult<Unit>>()
        commandChannel.trySend(FacadeCommand.Close(deferred))
        commandChannel.close()

        if (!hasMainLooper() || isMainThread()) {
            scope.launch {
                deferred.await()
                scope.cancel()
            }
        } else {
            runBlocking {
                deferred.await()
            }
            scope.cancel()
        }
    }

    private suspend fun handleStart(config: ScaleConfig): ScaleResult<Unit> {
        ensureOpen()
        val current = activeDriver
        if (current != null && current.isRunning() && currentConfig == config) {
            return ScaleResult.Ok(Unit)
        }

        mutableState.value = ScaleState.Starting(config)
        currentConfig = config

        return when (val result = resolveAndOpenDriver(config)) {
            is ScaleResult.Ok -> ScaleResult.Ok(Unit)
            is ScaleResult.Err -> {
                mutableState.value = ScaleState.Error(activeVendor, result.error)
                emitEvent(ScaleEvent.Error(result.error))
                result
            }
        }
    }

    private suspend fun handleReadOnce(): ScaleResult<WeightReading> {
        val driver = requireDriver() ?: return ScaleResult.Err(driverMissingError())
        return driver.readOnce()
    }

    private suspend fun handleTare(): ScaleResult<Unit> {
        val driver = requireDriver() ?: return ScaleResult.Err(driverMissingError())
        if (!driver.capabilities.tare) {
            return ScaleResult.Err(
                ScaleError.Unsupported(
                    message = "Vendor ${driver.vendor} does not support tare.",
                    vendor = driver.vendor,
                )
            )
        }
        return driver.tare()
    }

    private suspend fun handleZero(): ScaleResult<Unit> {
        val driver = requireDriver() ?: return ScaleResult.Err(driverMissingError())
        if (!driver.capabilities.zero) {
            return ScaleResult.Err(
                ScaleError.Unsupported(
                    message = "Vendor ${driver.vendor} does not support zero.",
                    vendor = driver.vendor,
                )
            )
        }
        return driver.zero()
    }

    private suspend fun handleStop(): ScaleResult<Unit> {
        ensureOpen()
        val stopResult = releaseActiveDriver()
        mutableCapabilities.value = ScaleCapabilities.NONE
        mutableState.value = ScaleState.Stopped(activeVendor)
        currentConfig = null
        activeFactory = null
        activeVendor = null
        activePort = null
        return stopResult
    }

    private suspend fun handleCloseInternal(): ScaleResult<Unit> {
        val result = releaseActiveDriver()
        mutableCapabilities.value = ScaleCapabilities.NONE
        mutableState.value = ScaleState.Closed
        currentConfig = null
        activeFactory = null
        activeVendor = null
        activePort = null
        return result
    }

    private suspend fun resolveAndOpenDriver(config: ScaleConfig): ScaleResult<Unit> {
        val candidates = orderedFactories(config.vendorPreference)
        if (candidates.isEmpty()) {
            return ScaleResult.Err(ScaleError.Configuration("No scale driver is registered."))
        }

        val errors = mutableListOf<String>()
        for (factory in candidates) {
            val portCandidates = resolvePortCandidates(factory, config)
            if (portCandidates.isEmpty()) {
                errors += "${factory.vendor}: no available port candidates."
                continue
            }

            for (port in portCandidates) {
                if (!shouldSkipProbe(config, factory, port)) {
                    val probeResult = probeFactory(factory, config, port)
                    if (probeResult is ScaleResult.Err) {
                        errors += "${factory.vendor}@${port.device}/${port.baudRate}: ${probeResult.error.message}"
                        continue
                    }
                }

                when (val openResult = factory.open(appContext, config, port, probeOnly = false)) {
                    is ScaleResult.Ok -> {
                        attachDriver(factory, openResult.value, port)
                        emitEvent(ScaleEvent.VendorSelected(factory.vendor))
                        emitEvent(
                            ScaleEvent.Info(
                                "Scale driver selected: ${factory.vendor} on ${port.device}@${port.baudRate}"
                            )
                        )
                        mutableState.value = ScaleState.Running(factory.vendor, openResult.value.capabilities)
                        return ScaleResult.Ok(Unit)
                    }

                    is ScaleResult.Err -> {
                        errors += "${factory.vendor}@${port.device}/${port.baudRate}: ${openResult.error.message}"
                    }
                }
            }
        }

        return ScaleResult.Err(
            ScaleError.DeviceUnavailable(
                message = buildString {
                    append("Unable to resolve a scale driver.")
                    if (errors.isNotEmpty()) {
                        append(' ')
                        append(errors.joinToString(separator = " | "))
                    }
                }
            )
        )
    }

    /**
     * 对已明确指定厂商和串口的场景，跳过 probe 避免无意义的 close -> reopen。
     *
     * JW 这类设备可能只在初始化后推送首帧重量；如果 probe 收到首帧后立即关闭，
     * 正式 driver 重新打开时就可能暂时拿不到重量，表现为“已连接但无读数”。
     */
    private fun shouldSkipProbe(
        config: ScaleConfig,
        factory: ScaleDriverFactory,
        port: ScalePortConfig,
    ): Boolean {
        val explicitPreference = config.vendorPreference as? ScaleVendorPreference.Explicit ?: return false
        val overriddenPort = config.portOverride ?: return false
        return explicitPreference.vendor == factory.vendor && overriddenPort == port
    }

    private suspend fun probeFactory(
        factory: ScaleDriverFactory,
        config: ScaleConfig,
        port: ScalePortConfig,
    ): ScaleResult<Unit> {
        val probeResult = factory.open(appContext, config, port, probeOnly = true)
        if (probeResult is ScaleResult.Err) {
            return probeResult
        }

        val driver = (probeResult as ScaleResult.Ok).value
        return try {
            val deadline = System.currentTimeMillis() + config.probeTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (driver.lastSignalAtMs.value > 0L) {
                    return ScaleResult.Ok(Unit)
                }
                delay(20L)
            }
            ScaleResult.Err(
                ScaleError.DeviceUnavailable(
                    message = "Probe timeout for ${factory.vendor} on ${port.device}@${port.baudRate}.",
                    vendor = factory.vendor,
                )
            )
        } finally {
            runCatching { driver.close() }
        }
    }

    private fun orderedFactories(preference: ScaleVendorPreference): List<ScaleDriverFactory> {
        return when (preference) {
            is ScaleVendorPreference.Auto -> {
                val order = listOf(ScaleVendor.JW, ScaleVendor.LY, ScaleVendor.SOHE)
                order.mapNotNull { vendor -> driverFactories.firstOrNull { it.vendor == vendor } }
            }

            is ScaleVendorPreference.Explicit -> {
                listOfNotNull(driverFactories.firstOrNull { it.vendor == preference.vendor })
            }
        }
    }

    /**
     * 根据当前配置解析某个厂商需要尝试的串口列表。
     *
     * 规则：
     * 1. 如果业务显式传入了 [ScaleConfig.portOverride]，优先只尝试这个端口；
     * 2. 否则按 driver 自己声明的默认候选表自动探测；
     * 3. 如果 driver 未声明候选表，则退回 SDK 内置兜底表。
     */
    private fun resolvePortCandidates(
        factory: ScaleDriverFactory,
        config: ScaleConfig,
    ): List<ScalePortConfig> {
        config.portOverride?.let { return listOf(it) }

        val declared = factory.defaultPortCandidates
        if (declared.isNotEmpty()) {
            return declared.distinct()
        }

        return ScalePortCatalog.defaultCandidatesFor(factory.vendor).distinct()
    }

    private fun attachDriver(
        factory: ScaleDriverFactory,
        driver: ScaleDriver,
        port: ScalePortConfig,
    ) {
        activeFactory = factory
        activeDriver = driver
        activeVendor = driver.vendor
        activePort = port
        mutableCapabilities.value = driver.capabilities
        bridgeJobs.forEach(Job::cancel)
        bridgeJobs = listOf(
            scope.launch {
                driver.events.collect { event ->
                    when (event) {
                        is ScaleEvent.Error -> mutableState.value = ScaleState.Error(driver.vendor, event.error)
                        is ScaleEvent.Disconnected -> mutableState.value = ScaleState.Error(
                            driver.vendor,
                            ScaleError.DeviceUnavailable(
                                message = "Scale driver disconnected.",
                                vendor = driver.vendor,
                            ),
                        )

                        else -> Unit
                    }
                    mutableEvents.emit(event)
                }
            },
            scope.launch {
                driver.readings.collect { reading ->
                    mutableReadings.emit(reading)
                }
            },
        )
    }

    private suspend fun releaseActiveDriver(): ScaleResult<Unit> {
        bridgeJobs.forEach(Job::cancel)
        bridgeJobs = emptyList()

        val driver = activeDriver
        activeDriver = null
        if (driver == null) {
            return ScaleResult.Ok(Unit)
        }

        return try {
            val stopResult = driver.stop()
            runCatching { driver.close() }
            stopResult
        } catch (exception: Throwable) {
            ScaleResult.Err(
                ScaleError.OperationFailed(
                    message = exception.message ?: "Failed to stop scale driver.",
                    vendor = activeVendor,
                    cause = exception,
                )
            )
        }
    }

    private suspend fun emitEvent(event: ScaleEvent) {
        mutableEvents.emit(event)
    }

    private fun requireDriver(): ScaleDriver? {
        ensureOpen()
        return activeDriver
    }

    private fun driverMissingError(): ScaleError {
        return ScaleError.Configuration("ScaleFacade has not been started yet.")
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw ScaleError.Closed()
        }
    }

    private fun isMainThread(): Boolean {
        return try {
            Looper.myLooper() == Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasMainLooper(): Boolean {
        return try {
            Looper.getMainLooper() != null
        } catch (_: Throwable) {
            false
        }
    }

    private sealed interface FacadeCommand {
        val completion: CompletableDeferred<*>

        data class Start(
            val config: ScaleConfig,
            override val completion: CompletableDeferred<ScaleResult<Unit>> = CompletableDeferred(),
        ) : FacadeCommand

        data class ReadOnce(
            override val completion: CompletableDeferred<ScaleResult<WeightReading>> = CompletableDeferred(),
        ) : FacadeCommand

        data class Tare(
            override val completion: CompletableDeferred<ScaleResult<Unit>> = CompletableDeferred(),
        ) : FacadeCommand

        data class Zero(
            override val completion: CompletableDeferred<ScaleResult<Unit>> = CompletableDeferred(),
        ) : FacadeCommand

        data class Stop(
            override val completion: CompletableDeferred<ScaleResult<Unit>> = CompletableDeferred(),
        ) : FacadeCommand

        data class Close(
            override val completion: CompletableDeferred<ScaleResult<Unit>>,
        ) : FacadeCommand
    }

    private suspend fun <T> submit(command: FacadeCommand): T {
        ensureOpen()
        commandChannel.send(command)
        @Suppress("UNCHECKED_CAST")
        return (command.completion as CompletableDeferred<T>).await()
    }

    private fun <T> FacadeCommand.complete(result: T) {
        @Suppress("UNCHECKED_CAST")
        (completion as CompletableDeferred<T>).complete(result)
    }
}
