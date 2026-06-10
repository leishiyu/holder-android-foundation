# Camera SDK

`hardware/camera` 是当前相机能力的主交付模块。它面向“外部 App 直接接入 SDK”设计，默认交付一个可直接集成的主 AAR，同时把 Compose 预览包装和 MLKit 人脸分析拆成可选 sidecar AAR。

## 模块结构

```text
hardware/camera
  api             # 公开类型、配置模型、错误模型、预览宿主契约
  core            # CameraController 状态机、能力协商、资源回收
  view            # CameraPreviewView
  compose         # CameraPreview(controller, modifier)
  driver-camerax  # 默认内置相机实现，基于输出帧保存 snapshot
  driver-camera2  # Camera2 fallback，基于 ImageReader 输出帧保存 snapshot
  driver-camera1  # Camera1 compatibility fallback，基于 NV21 预览帧保存 snapshot
  driver-uvc      # UVC 驱动、USBMonitor、native so、device filter
  face-mlkit      # 可选人脸分析扩展
```

## 对外产物

- 主 SDK：`hardware-camera-sdk-<version>-release.aar`
- Compose 扩展：`hardware-camera-compose-<version>-release.aar`
- MLKit 扩展：`hardware-camera-face-mlkit-<version>-release.aar`

当前 camera family 版本来源于 [version.properties](/D:/dev/code/holder-android-foundation/hardware/camera/version.properties)。

## 对外 API

主 SDK 对外只暴露高层控制器和配置模型，不暴露具体 driver 实现：

- `CameraControllerFactory`
- `CameraController`
- `CameraConfig`
- `CameraBackendPreference`
- `LensFacing`
- `FrameDeliveryConfig`
- `CaptureRequest`
- `CaptureResult`
- `CameraState`
- `CameraEvent`
- `CameraCapability`
- `CameraException`
- `CameraFrame`

## 最小接入

### 1. 作为源码模块依赖

```kotlin
dependencies {
    implementation(project(":hardware:camera"))
    implementation(project(":hardware:camera:compose"))
}
```

### 2. 作为本地 AAR 依赖

```kotlin
dependencies {
    implementation(files("libs/hardware-camera-sdk-1.0.0-release.aar"))
    implementation(files("libs/hardware-camera-compose-1.0.0-release.aar"))
}
```

如果只接传统 `View` 容器，可以只引主 SDK；只有在使用 Compose 预览包装时才需要额外引入 compose sidecar。

## 最小示例

```kotlin
val controller = CameraControllerFactory.create(
    context = applicationContext,
    config = CameraConfig(
        backendPreference = CameraBackendPreference.AUTO,
        lensFacing = LensFacing.BACK,
        captureSize = CameraSize.HD_720P,
        jpegQuality = 95,
        frameRotationDegrees = 0,
        uvcFrameConfig = UvcFrameConfig(
            yuvLayout = UvcYuvLayout.AUTO,
        ),
        enableLogging = true,
    )
)

CameraPreview(
    controller = controller,
    modifier = Modifier.fillMaxSize(),
)

val result = controller.capture(
    CaptureRequest.Snapshot(
        outputFile = File(
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "camera-sdk/manual_capture.jpg"
        )
    )
)

val cameras = controller.queryAvailableCameras()
controller.switchToNextCamera()
```

对应的资源释放边界：

- 每个页面单独创建一个 `CameraController`
- 页面销毁时必须调用 `controller.close()`
- Compose 包装会负责预览 host 的绑定与解绑，但不会替宿主持有 controller 生命周期

## 能力语义

- `CaptureRequest.Snapshot`
  - 所有 backend 统一保存当前相机输出帧
  - Camera2 使用 `ImageReader` 帧，Camera1/UVC 使用 frame callback 帧，CameraX 使用 `ImageAnalysis` 帧
  - 不再暴露 still capture / view bitmap 截图语义
- `queryAvailableCameras()`
  - 返回当前 backend 可选相机列表
- `switchToNextCamera()`
  - 在当前 backend 内轮换到下一个相机
- `Snapshot` 支持可选 `outputFile`
  - 不传时默认写入 `cacheDir/camera-sdk`
  - 传入后按调用方指定路径落盘，并自动创建父目录

## 图像质量与方向

- `CameraConfig.captureSize`
  - 为空时使用低端机友好的默认尺寸：CameraX/Camera2/Camera1 为 `1280x720`，UVC 为 `640x360`
  - 传入 `CameraSize(width, height)` 时，各 backend 会选择最接近的设备支持尺寸
- `CameraConfig.jpegQuality`
  - 控制 snapshot JPEG 压缩质量，范围 `1..100`，默认 `95`
- `CameraConfig.frameRotationDegrees`
  - 用于校准设备输出帧方向，可选 `0`、`90`、`180`、`270`
  - 对外 `frames` 会携带校准后的旋转角；保存 snapshot 时会将该旋转实际应用到 JPEG 像素
- `CameraConfig.uvcFrameConfig`
  - 仅影响 UVC backend，用于处理不同 UVC 设备输出的 YUV420SP UV 顺序差异
  - 默认 `UvcYuvLayout.AUTO` 会在早期帧上启发式判断；置信度不足时回退到 `NV12_TO_NV21`
  - 如果某些机型颜色仍偏绿、偏紫，可显式切到 `UvcYuvLayout.NV21_DIRECT` 或 `NV12_TO_NV21`

## 存储行为

- 默认输出目录：
  - `<app cache dir>/camera-sdk`
- 指定输出路径：
  - 通过 `CaptureRequest.Snapshot(outputFile = File(...))` 传入
- `CaptureResult.path`
  - 始终返回最终实际落盘路径

## 后端策略

- 内置相机默认优先 `CameraX`
- `CameraX` 不可用时回退 `Camera2`
- `Camera2` 不可用时最终回退 `Camera1`
- `UVC` 不参与内置相机自动兜底，只会在显式指定 `backendPreference = UVC` 时启用

## 权限与清单

- 消费端 App 需要声明并在运行时申请 `android.permission.CAMERA`
- 使用 UVC 时，需要让宿主设备支持 USB Host，并按业务场景补充设备选择逻辑
- `driver-uvc` 自己维护 `device_filter.xml` 和对应底层依赖，主 SDK 不再把 UVC 资源散落到其他模块

## 推荐验证命令

```bash
./gradlew :hardware:camera:assembleRelease
./gradlew :hardware:camera:compose:assembleRelease
./gradlew :samples:sample-camera-sdk:assembleDebug
```

更完整的接入说明见 [docs/foundation-integration-guide.md](/D:/dev/code/holder-android-foundation/docs/foundation-integration-guide.md)。
