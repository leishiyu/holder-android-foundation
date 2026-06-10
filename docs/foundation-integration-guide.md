# holder-android-foundation 接入指南

本文基于 `2026-04-02` 当前仓库实际代码整理，目标是回答两件事：

- `holder-android-foundation` 现在到底交付了什么
- 另一个 Android 仓库如果要接入这套基础框架或硬件 SDK，应该怎么接

本文只覆盖当前已经落地的结构和能力，不描述未来规划。

## 1. 当前仓库结构

```text
holder-android-foundation
  build-logic
  core
    build.gradle.kts
    network
    platform
    ui
  hardware
    base
    cabinet
    camera
    scale
    temperature
  samples
    sample-cabinet
    sample-camera-sdk
  docs
```

当前 `settings.gradle.kts` 中实际启用的模块如下：

- `:core`
- `:hardware:base`
- `:hardware:cabinet`
- `:hardware:camera`
- `:hardware:camera:api`
- `:hardware:camera:core`
- `:hardware:camera:view`
- `:hardware:camera:compose`
- `:hardware:camera:driver-camerax`
- `:hardware:camera:driver-camera2`
- `:hardware:camera:driver-camera1`
- `:hardware:camera:driver-uvc`
- `:hardware:camera:face-mlkit`
- `:hardware:scale`
- `:hardware:vendor`
- `:hardware:temperature`
- `:samples:sample-cabinet`
- `:samples:sample-camera-sdk`

## 2. 当前模块分层

### 2.1 core

`core` 现在分成两层：

- `:core`
  对外聚合出口，负责产出 `foundation-core-sdk`
- `core/network`
  负责 Retrofit、OkHttp、序列化、Hilt、WorkManager 这一层通用网络与后台任务能力
- `core/platform`
  负责 MMKV、logger、工具类、平台侧基础封装
- `core/ui`
  负责 Compose UI、通用页面容器与 UI 相关能力

`navigation` 已经回收到 `ui` 源码层，不再单独维护成一个 module。

当前依赖方向是：

- `ui -> platform`
- `network -> platform`

也就是说，`core` 现在既保留了源码分层，也新增了一个真正可分发的聚合 SDK 出口。

### 2.2 hardware

#### `:hardware:camera`

这是当前最成熟的 SDK 家族，已经是明确的消费边界：

- 主 AAR：`hardware-camera-sdk`
- 可选 Compose 扩展：`hardware-camera-compose`
- 可选 MLKit 扩展：`hardware-camera-face-mlkit`

主模块 `:hardware:camera` 会聚合：

- `api`
- `core`
- `view`
- `driver-camerax`
- `driver-camera2`
- `driver-camera1`
- `driver-uvc`

#### `:hardware:cabinet`

这是当前留样柜一体机 SDK 的主交付模块：

- 对外主 AAR：`hardware-cabinet`
- 标准 `src/main` 目录结构
- JW / Star 源码与打印能力都已经回收到这个单 module 内

当前 `cabinet` 的结构重点是：

- 对外只暴露 `CabinetFacade`
- 内部通过 `core / driver / print / internal` 包结构分层
- 共享串口底层二进制由内部模块 `:hardware:vendor` 统一承接，业务侧不需要单独依赖

#### `:hardware:scale`

这是当前称重 SDK 的主交付模块：

- 对外主 AAR：`hardware-scale`
- 标准 `src/main` 目录结构
- 内部通过 `core / driver / internal` 包结构分层
- JW 厂商二进制已回收到 `hardware-scale`，共享串口底层二进制由 `:hardware:vendor` 承接

#### `:hardware:vendor`

这是一个内部共享模块，不是业务侧推荐接入边界：

- 只承接 `cabinet` 与 `scale` 共用的 vendor 二进制
- 目的是避免两个 SDK 在同一个宿主工程里同时接入时出现 duplicate class
- 业务侧正常只依赖 `hardware-cabinet` / `hardware-scale`，它会作为传递依赖被自动带上

#### `:hardware:temperature`

这个模块目前仍然是旧式 wrapper 形态：

- 仍然直接使用本地 `libs/*.jar` 和 `libs/*.aar`
- 还没有像 `camera / cabinet / scale` 一样整理成最终 SDK 交付边界

因此，`temperature` 当前不建议作为“外部仓稳定接入 SDK”来使用。

#### `:hardware:base`

这个模块仍然保留在仓库中，但从现在的实际接入方式来看：

- `cabinet` 已经不再依赖它对外暴露能力
- `sample-cabinet` 也不再直接依赖它

所以它更像是历史兼容与共享契约保留层，而不是当前推荐的外部接入边界。

## 3. 当前版本管理

### 3.1 core

`core` 当前仍使用仓库级版本文件：

- 根目录 `version.properties`

可通过下面命令查看：

```powershell
.\gradlew.bat printCoreVersion
.\gradlew.bat printCoreSdkVersion
```

### 3.2 hardware

每个 `hardware/*` 模块都有自己的 `version.properties`。

截至当前代码，版本统一为：

| Module | Version | Artifact |
| --- | --- | --- |
| `hardware/base` | `1.0.1` | `hardware-base` |
| `hardware/cabinet` | `1.0.1` | `hardware-cabinet` |
| `hardware/camera` | `1.0.1` | `hardware-camera-sdk` |
| `hardware/scale` | `1.0.1` | `hardware-scale` |
| `hardware/temperature` | `1.0.1` | `hardware-temperature` |

可通过下面命令查看：

```powershell
.\gradlew.bat printHardwareVersions
.\gradlew.bat printCabinetSdkVersion
.\gradlew.bat printCameraSdkVersions
.\gradlew.bat printScaleSdkVersion
```

## 4. 当前可用打包任务

### 4.1 通用任务

```powershell
.\gradlew.bat projects
.\gradlew.bat printCoreVersion
.\gradlew.bat printCoreSdkVersion
.\gradlew.bat assembleCoreSdkRelease
.\gradlew.bat printHardwareVersions
.\gradlew.bat assembleHardwareRelease
```

### 4.2 Camera SDK

```powershell
.\gradlew.bat :hardware:camera:assembleRelease
.\gradlew.bat :hardware:camera:compose:assembleRelease
.\gradlew.bat :hardware:camera:face-mlkit:assembleRelease
.\gradlew.bat assembleCameraSdkRelease
```

### 4.3 Cabinet SDK

```powershell
.\gradlew.bat :hardware:cabinet:assembleRelease
.\gradlew.bat assembleCabinetSdkRelease
```

### 4.4 Scale SDK

```powershell
.\gradlew.bat :hardware:scale:assembleRelease
.\gradlew.bat assembleScaleSdkRelease
```

### 4.5 Samples

```powershell
.\gradlew.bat :samples:sample-cabinet:assembleDebug
.\gradlew.bat :samples:sample-camera-sdk:assembleDebug
```

## 5. 当前交付边界

为了方便判断“另一个仓库该怎么接”，可以直接按下面这张表理解当前状态：

| 能力 | 当前推荐交付方式 | 当前是否适合外部仓直接接入 |
| --- | --- | --- |
| `core` | 本地 AAR 或后续 Maven 化 | 是，但建议只接聚合 `core-sdk` |
| `hardware:camera` | 本地 AAR | 是 |
| `hardware:camera:compose` | 本地 AAR | 是，可选 |
| `hardware:camera:face-mlkit` | 本地 AAR | 是，可选 |
| `hardware:cabinet` | 本地 AAR | 是 |
| `hardware:scale` | 本地 AAR | 是 |
| `hardware:temperature` | 暂不建议 | 否 |

这里最重要的结论是：

- `camera / cabinet / scale` 现在都已经是“主聚合 AAR”交付形态
- `core` 已经有聚合 `core-sdk` 出口
- `temperature` 还没有进入最终 SDK 形态

## 6. 外部仓接入方式

如果你在另一个 Android 仓库中创建 App，当前推荐只分两类接入方式：

- `硬件 SDK`：优先走本地 AAR
- `core`：优先走聚合 `core-sdk` AAR，而不是手动拼多个子模块

### 6.1 本地 AAR 接入通用步骤

#### 第一步：在 foundation 仓打包

以 `core / camera / cabinet / scale` 为例：

```powershell
.\gradlew.bat assembleCoreSdkRelease
.\gradlew.bat assembleCameraSdkRelease
.\gradlew.bat assembleCabinetSdkRelease
.\gradlew.bat assembleScaleSdkRelease
```

#### 第二步：把产物复制到宿主工程

建议复制到宿主工程的：

```text
<consumer-app>/app/libs/
```

#### 第三步：在宿主工程中添加依赖

示例：

```kotlin
dependencies {
    implementation(files("libs/foundation-core-sdk-1.0.1-release.aar"))
    implementation(files("libs/hardware-camera-sdk-1.0.1-release.aar"))
    implementation(files("libs/hardware-camera-compose-1.0.1-release.aar"))
    implementation(files("libs/hardware-cabinet-1.0.1-release.aar"))
    implementation(files("libs/hardware-scale-1.0.1-release.aar"))
}
```

#### 第四步：补齐外部 Maven 依赖

这一步非常重要。

因为当前是“本地 file AAR”接入，不是 Maven 发布，所以：

- AAR 本身不会给宿主工程带出完整的 Maven 传递依赖信息
- 宿主工程必须自己补齐运行时所需依赖

如果不补，最常见的结果是：

- 编译期找不到外部类
- 运行期缺类
- Compose / CameraX / MLKit 相关能力不能正常工作

## 7. 本地 AAR 接入时必须注意的依赖边界

### 7.1 Core SDK 额外说明

`foundation-core-sdk` 当前会聚合这些源码目录：

- `ui`
- `network`
- `platform`

它适合作为宿主工程的“基础脚手架入口”，避免再手动拼多个内部层目录。

### 7.2 所有 SDK AAR 的公共依赖

由于 foundation 的 library convention plugin 默认给所有 Android library 注入了下面这组基础依赖，宿主工程建议显式补齐：

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

### 7.3 Camera SDK 额外依赖

`hardware-camera-sdk` 当前还依赖 CameraX 运行时库，所以宿主工程还需要补齐：

```kotlin
dependencies {
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.camera:camera-extensions:1.5.1")
}
```

如果宿主还使用 `hardware-camera-compose`，建议补齐：

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
```

如果宿主还使用 `hardware-camera-face-mlkit`，再补：

```kotlin
dependencies {
    implementation("com.google.mlkit:face-detection:16.1.7")
}
```

### 7.4 Cabinet SDK 额外说明

`hardware-cabinet` 当前已经把柜门、打印、称重、温度相关厂商能力收口在 SDK 内部：

- `JW SDK`
- `JW Serial`
- `Star`
- 共享串口底层通过内部 `:hardware:vendor` 带入

对宿主工程来说，当前重点不是再额外引 vendor AAR，而是：

- 接主 AAR
- 补齐公共 Kotlin / AndroidX 依赖
- 根据业务实现自己的 `CalibrationStore`、`CabinetLogger`

### 7.5 Scale SDK 额外说明

`hardware-scale` 当前已经把下面这部分能力收口到主 SDK：

- `JW`
- `LY`
- 共享串口底层通过内部 `:hardware:vendor` 带入

对宿主工程来说，当前重点同样是：

- 接主 AAR
- 补齐公共 Kotlin / AndroidX 依赖

## 8. 当前各 SDK 的输出物

### 8.1 Core SDK

输出目录：

```text
core/build/outputs/aar
```

当前产物名规则：

- `foundation-core-sdk-<version>-release.aar`

### 8.2 Camera family

输出目录：

```text
hardware/camera/build/outputs/aar
hardware/camera/compose/build/outputs/aar
hardware/camera/face-mlkit/build/outputs/aar
```

当前产物名规则：

- `hardware-camera-sdk-<version>-release.aar`
- `hardware-camera-compose-<version>-release.aar`
- `hardware-camera-face-mlkit-<version>-release.aar`

### 8.3 Cabinet SDK

输出目录：

```text
hardware/cabinet/build/outputs/aar
```

当前产物名规则：

- `hardware-cabinet-<version>-release.aar`

### 8.4 Scale SDK

输出目录：

```text
hardware/scale/build/outputs/aar
```

当前产物名规则：

- `hardware-scale-<version>-release.aar`

## 9. 当前 sample 的作用

### 9.1 `sample-cabinet`

依赖关系：

```kotlin
implementation(project(":core"))
implementation(project(":hardware:cabinet"))
```

它的作用是：

- 演示 foundation 脚手架最小接线
- 演示 Cabinet SDK 的基础启动与控制
- 作为留样柜宿主 App 的最小接入参考

### 9.2 `sample-camera-sdk`

依赖关系：

```kotlin
implementation(project(":hardware:camera"))
implementation(project(":hardware:camera:compose"))
```

它的作用是：

- 演示 Camera SDK 接入
- 演示 Compose 预览
- 演示相机启动、切换、拍照、指定输出路径

## 10. 现在应该怎么选

### 场景 A：只想在另一个仓库里接相机

推荐：

- 接 `hardware-camera-sdk`
- 如需 Compose 预览，再接 `hardware-camera-compose`
- 如需 MLKit 人脸，再接 `hardware-camera-face-mlkit`

### 场景 B：想接留样柜一体机能力

推荐：

- 接 `hardware-cabinet`
- 业务侧通过 `CabinetFacade` 统一访问 door / scale / printer / temperature

### 场景 C：只想接称重能力

推荐：

- 接 `hardware-scale`

### 场景 D：想把 foundation 整套 core 脚手架跨仓复用

当前推荐：

- 直接接 `foundation-core-sdk`
- 不要再手动拼内部源码层

## 11. 当前不建议做的事情

- 不建议把 `:hardware:temperature` 当成最终 SDK 直接对外发
- 不建议把 `core` 继续拆成一堆 file AAR 或内部层手动塞进另一个业务仓
- 不建议假设“本地 AAR 会自动带出所有三方依赖”

## 12. 推荐阅读顺序

如果你要真正接入，建议按这个顺序看：

1. 先看根目录 `README.md`
2. 再看具体模块的 README
3. 再看 sample

当前最值得直接参考的文件是：

- `README.md`
- `core/README.md`
- `hardware/camera/README.md`
- `hardware/cabinet/README.md`
- `hardware/scale/README.md`
- `samples/sample-camera-sdk`
- `samples/sample-cabinet`

## 13. 一句话结论

按当前最新代码来看：

- `camera / cabinet / scale` 已经可以按“主 AAR”思路对外接入
- `core` 已经可以通过 `foundation-core-sdk` 作为聚合 AAR 对外接入
- `temperature` 还没有进入最终 SDK 交付形态
- 只要是“本地 file AAR”接入，宿主工程都必须自己补齐外部 Maven 依赖
