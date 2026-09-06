package com.holderzone.hardware.scale.driver.sohe

import android.content.Context
import android_serialport_api.SerialPort
import com.holderzone.hardware.scale.ScaleCapabilities
import com.holderzone.hardware.scale.ScaleConfig
import com.holderzone.hardware.scale.ScaleError
import com.holderzone.hardware.scale.ScaleEvent
import com.holderzone.hardware.scale.ScaleLogger
import com.holderzone.hardware.scale.ScalePortConfig
import com.holderzone.hardware.scale.ScaleResult
import com.holderzone.hardware.scale.ScaleVendor
import com.holderzone.hardware.scale.WeightReading
import com.holderzone.hardware.scale.internal.ScalePortCatalog
import com.holderzone.hardware.scale.internal.spi.ScaleDriver
import com.holderzone.hardware.scale.internal.spi.ScaleDriverFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class SoheScaleDriverFactory : ScaleDriverFactory {
    override val vendor: ScaleVendor = ScaleVendor.SOHE
    override val defaultPortCandidates: List<ScalePortConfig> =
        ScalePortCatalog.defaultCandidatesFor(vendor)

    override suspend fun open(
        appContext: Context,
        config: ScaleConfig,
        port: ScalePortConfig,
        probeOnly: Boolean,
    ): ScaleResult<ScaleDriver> {
        return SoheScaleDriver.create(config, port)
    }
}

/**
 * 首衡串口称重 driver。
 *
 * 首衡设备默认连续发送重量帧，因此 `readOnce()` 延续 SDK 统一语义，返回最近一次
 * 有效重量，而不是主动发送 R 指令。
 */
internal class SoheScaleDriver private constructor(
    private val config: ScaleConfig,
    private val port: ScalePortConfig,
) : ScaleDriver {

    override val vendor: ScaleVendor = ScaleVendor.SOHE

    override val capabilities: ScaleCapabilities = ScaleCapabilities(
        selectedVendor = vendor,
        readWeight = true,
        streamWeight = true,
        tare = true,
        zero = true,
        stableSignal = true,
    )

    private val logger: ScaleLogger = config.logger
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val parser = SoheWeightFrameParser()

    private val mutableEvents = MutableSharedFlow<ScaleEvent>(extraBufferCapacity = 32)
    private val mutableReadings = MutableSharedFlow<WeightReading>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    private val mutableLastSignalAtMs = MutableStateFlow(0L)

    override val events: Flow<ScaleEvent> = mutableEvents
    override val readings: Flow<WeightReading> = mutableReadings
    override val lastSignalAtMs = mutableLastSignalAtMs.asStateFlow()

    private var serialPort: SerialPort? = null
    private var readJob: Job? = null
    private var latestReading: WeightReading? = null

    companion object {
        private const val TAG = "SoheScaleDriver"

        suspend fun create(
            config: ScaleConfig,
            port: ScalePortConfig,
        ): ScaleResult<ScaleDriver> {
            val driver = SoheScaleDriver(config, port)
            return driver.start()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override suspend fun stop(): ScaleResult<Unit> = shutdown()

    override fun close() {
        runBlocking {
            shutdown()
        }
    }

    override suspend fun readOnce(): ScaleResult<WeightReading> {
        val reading = latestReading
        return if (reading != null) {
            ScaleResult.Ok(reading)
        } else {
            ScaleResult.Err(
                ScaleError.DeviceUnavailable(
                    message = "No SOHE weight reading is available yet.",
                    vendor = vendor,
                )
            )
        }
    }

    override suspend fun tare(): ScaleResult<Unit> {
        return sendCommand(SoheProtocol.tareCommand())
    }

    override suspend fun zero(): ScaleResult<Unit> {
        return sendCommand(SoheProtocol.zeroCommand())
    }

    private suspend fun start(): ScaleResult<ScaleDriver> {
        return runCatching {
            serialPort = SerialPort(File(port.device), port.baudRate, 0)
            running.set(true)
            mutableEvents.emit(ScaleEvent.Connected(vendor))
            readJob = scope.launch {
                readLoop()
            }
            logger.info(TAG, "SOHE scale driver started on ${port.device}@${port.baudRate}")
            ScaleResult.Ok(this as ScaleDriver)
        }.getOrElse { throwable ->
            shutdown()
            ScaleResult.Err(
                ScaleError.DeviceUnavailable(
                    message = throwable.message ?: "Failed to initialize SOHE scale driver.",
                    vendor = vendor,
                    cause = throwable,
                )
            )
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(512)
        while (running.get()) {
            val port = serialPort ?: break
            val size = try {
                port.inputStream.read(buffer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                handleReadFailure(exception)
                break
            } catch (throwable: Throwable) {
                handleReadFailure(throwable)
                break
            }

            if (size <= 0) {
                continue
            }

            parser.append(buffer, size).forEach { frame ->
                when (frame) {
                    is SoheParsedFrame.Weight -> publishReading(
                        WeightReading(
                            grams = frame.grams,
                            stable = frame.stable,
                            netMode = frame.netMode,
                            zero = frame.zero,
                            rawFrame = frame.rawFrame,
                        )
                    )

                    is SoheParsedFrame.Tare -> {
                        logger.info(
                            TAG,
                            "SOHE custom tare received grams=${frame.grams} raw=${frame.rawFrame}",
                        )
                    }
                }
            }
        }
    }

    private fun publishReading(reading: WeightReading) {
        latestReading = reading
        mutableLastSignalAtMs.value = System.currentTimeMillis()
        mutableReadings.tryEmit(reading)
        mutableEvents.tryEmit(ScaleEvent.WeightUpdated(reading))
        logger.debug(
            TAG,
            "SOHE weight received grams=${reading.grams} stable=${reading.stable} " +
                "netMode=${reading.netMode} raw=${reading.rawFrame}",
        )
    }

    private suspend fun sendCommand(command: ByteArray): ScaleResult<Unit> {
        val port = serialPort
            ?: return ScaleResult.Err(
                ScaleError.DeviceUnavailable(
                    message = "SOHE serial port is unavailable.",
                    vendor = vendor,
                )
            )

        return withContext(Dispatchers.IO) {
            runCatching {
                writeMutex.withLock {
                    port.outputStream.write(command)
                    port.outputStream.flush()
                }
                logger.debug(TAG, "SOHE command sent: ${command.toHexString()}")
                ScaleResult.Ok(Unit)
            }.getOrElse { throwable ->
                ScaleResult.Err(
                    ScaleError.OperationFailed(
                        message = throwable.message ?: "Failed to send SOHE scale command.",
                        vendor = vendor,
                        cause = throwable,
                    )
                )
            }
        }
    }

    private fun handleReadFailure(throwable: Throwable) {
        if (!running.get()) {
            return
        }
        logger.error(TAG, "SOHE scale read loop failed.", throwable)
        mutableEvents.tryEmit(
            ScaleEvent.Error(
                ScaleError.Communication(
                    message = throwable.message ?: "SOHE scale read loop failed.",
                    vendor = vendor,
                    cause = throwable,
                )
            )
        )
    }

    private fun shutdown(): ScaleResult<Unit> {
        return runCatching {
            running.set(false)
            readJob?.cancel()
            readJob = null
            scope.coroutineContext.cancelChildren()
            serialPort?.close()
            serialPort = null
            parser.reset()
            latestReading = null
            mutableLastSignalAtMs.value = 0L
            mutableEvents.tryEmit(ScaleEvent.Disconnected(vendor))
            logger.info(TAG, "SOHE scale driver stopped")
            ScaleResult.Ok(Unit)
        }.getOrElse { throwable ->
            ScaleResult.Err(
                ScaleError.OperationFailed(
                    message = throwable.message ?: "Failed to stop SOHE scale driver.",
                    vendor = vendor,
                    cause = throwable,
                )
            )
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { "%02X".format(it) }
    }
}
