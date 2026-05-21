package com.holderzone.samples.camerasdk.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.holderzone.hardware.camera.CameraBackendPreference
import com.holderzone.hardware.camera.CameraCapability
import com.holderzone.hardware.camera.CameraConfig
import com.holderzone.hardware.camera.CameraController
import com.holderzone.hardware.camera.CameraControllerFactory
import com.holderzone.hardware.camera.CameraEvent
import com.holderzone.hardware.camera.CameraState
import com.holderzone.hardware.camera.CaptureRequest
import com.holderzone.hardware.camera.LensFacing
import com.holderzone.hardware.camera.UvcFrameConfig
import com.holderzone.hardware.camera.UvcYuvLayout
import com.holderzone.hardware.camera.compose.CameraPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SampleCameraSdkRoot() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFEAF2FF),
                            Color(0xFFF8FBFF),
                            Color.White,
                        )
                    )
                )
        ) {
            if (hasCameraPermission) {
                CameraSdkSampleScreen()
            } else {
                PermissionRequiredScreen(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraSdkSampleScreen() {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) {
        CameraControllerFactory.create(
            context = context.applicationContext,
            config = CameraConfig(
                backendPreference = CameraBackendPreference.AUTO,
                lensFacing = LensFacing.BACK,
                uvcFrameConfig = UvcFrameConfig(
                    yuvLayout = UvcYuvLayout.AUTO,
                ),
                enableLogging = false,
            )
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val capabilities by controller.capabilities.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var latestEvent by remember { mutableStateOf("Waiting for preview host binding...") }
    var lastCapturePath by remember { mutableStateOf("No capture yet") }
    var requestedLens by remember { mutableStateOf(LensFacing.BACK) }

    DisposableEffect(controller) {
        // The sample owns the controller lifecycle explicitly so consumers can copy this safely.
        onDispose {
            controller.close()
        }
    }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            latestEvent = event.toReadableText()
            if (event is CameraEvent.CaptureCompleted) {
                lastCapturePath = event.result.path
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderCard()
        PreviewCard(controller = controller)
        StatusCard(
            state = state,
            capabilities = capabilities,
            latestEvent = latestEvent,
            lastCapturePath = lastCapturePath,
        )
        ActionCard(
            canSwitchLens = capabilities.switchLens,
            canSwitchCamera = capabilities.switchCamera,
            onStart = {
                scope.launchControllerCommand(
                    controller = controller,
                    onFailure = {
                        latestEvent = "Start failed: ${it.message ?: it::class.java.simpleName}"
                    }
                ) {
                    start()
                }
            },
            onStop = {
                scope.launchControllerCommand(
                    controller = controller,
                    onFailure = {
                        latestEvent = "Stop failed: ${it.message ?: it::class.java.simpleName}"
                    }
                ) {
                    stop()
                }
            },
            onCapture = {
                scope.launchControllerCommand(
                    controller = controller,
                    onFailure = {
                        latestEvent = "Capture failed: ${it.message ?: it::class.java.simpleName}"
                    }
                ) {
                    capture(
                        CaptureRequest.Snapshot(
                            outputFile = context.createSampleCaptureFile()
                        )
                    )
                }
            },
            onSwitchLens = {
                requestedLens = if (requestedLens == LensFacing.BACK) {
                    LensFacing.FRONT
                } else {
                    LensFacing.BACK
                }
                scope.launchControllerCommand(
                    controller = controller,
                    onFailure = {
                        latestEvent =
                            "Switch lens failed: ${it.message ?: it::class.java.simpleName}"
                    }
                ) {
                    switchLens(requestedLens)
                }
            },
            onSwitchCamera = {
                scope.launchControllerCommand(
                    controller = controller,
                    onFailure = {
                        latestEvent =
                            "Switch camera failed: ${it.message ?: it::class.java.simpleName}"
                    }
                ) {
                    switchToNextCamera()
                }
            }
        )
    }
}

@Composable
private fun HeaderCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Camera SDK Sample",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "这个 sample 只验证新的 camera SDK 接线：controller 创建、Compose 预览、状态流、启动停止和拍照结果。Capture 会演示指定输出路径。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PreviewCard(
    controller: CameraController,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleLarge
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF0F172A)
            ) {
                CameraPreview(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = "默认使用 AUTO 策略，优先 CameraX，不可用时回退 Camera2。Capture 会写入应用专属图片目录下的 camera-sdk 文件夹。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: CameraState,
    capabilities: CameraCapability,
    latestEvent: String,
    lastCapturePath: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "State",
                style = MaterialTheme.typography.titleLarge
            )
            StatusLine(label = "State", value = state.toReadableText())
            StatusLine(label = "Switch Lens", value = capabilities.switchLens.toEnabledText())
            StatusLine(label = "Switch Camera", value = capabilities.switchCamera.toEnabledText())
            StatusLine(
                label = "Snapshot",
                value = capabilities.snapshotCapture.toEnabledText()
            )
            StatusLine(
                label = "Frame Streaming",
                value = capabilities.frameStreaming.toEnabledText()
            )
            StatusLine(label = "Latest Event", value = latestEvent)
            StatusLine(label = "Last Capture", value = lastCapturePath)
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionCard(
    canSwitchLens: Boolean,
    canSwitchCamera: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCapture: () -> Unit,
    onSwitchLens: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleLarge
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onStart,
                ) {
                    Text("Start")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onStop,
                ) {
                    Text("Stop")
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = onCapture,
                ) {
                    Text("Capture")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchLens,
                    enabled = canSwitchLens,
                ) {
                    Text("Switch Lens")
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchCamera,
                    enabled = canSwitchCamera,
                ) {
                    Text("Next Camera")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PermissionRequiredScreen(
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Camera permission required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Sample 会在权限通过后创建 controller，避免把权限失败和 SDK 生命周期混在一起。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestPermission) {
                    Text("Grant Camera Permission")
                }
            }
        }
    }
}

private fun CameraState.toReadableText(): String {
    return when (this) {
        CameraState.Idle -> "Idle"
        CameraState.Closed -> "Closed"
        is CameraState.Bound -> "Bound (${backend.name})"
        is CameraState.Starting -> "Starting (${backend.name})"
        is CameraState.Previewing -> "Previewing (${backend.name})"
        is CameraState.Stopped -> "Stopped (${backend.name})"
        is CameraState.Error -> "Error (${backend?.name ?: "none"}): ${exception.message}"
    }
}

private fun CameraEvent.toReadableText(): String {
    return when (this) {
        is CameraEvent.BackendSelected -> "Backend selected: ${backend.name}"
        is CameraEvent.PreviewStarted -> "Preview started: ${backend.name}"
        is CameraEvent.PreviewStopped -> "Preview stopped: ${backend.name}"
        is CameraEvent.CaptureCompleted -> {
            "Capture completed: ${result.kind.name} via ${result.backend.name}"
        }

        is CameraEvent.Info -> message
        is CameraEvent.Error -> exception.message ?: "Unknown camera error"
    }
}

private fun Boolean.toEnabledText(): String = if (this) "Enabled" else "Not supported"

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.createSampleCaptureFile(): File {
    val root = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
    val directory = File(root, "camera-sdk").apply { mkdirs() }
    return File(directory, "sample_capture_${System.currentTimeMillis()}.jpg")
}

private fun CoroutineScope.launchControllerCommand(
    controller: CameraController,
    onFailure: (Throwable) -> Unit = {},
    block: suspend CameraController.() -> Any?,
) {
    launch {
        runCatching {
            controller.block()
        }.onFailure(onFailure)
    }
}
