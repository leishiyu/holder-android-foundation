# Scale SDK

`hardware-scale` 是面向称重设备的统一 SDK。它对外暴露一个 `ScaleFacade`，用于抹平 JW、亮悦和首衡三套称重实现差异，并以可独立打包的 AAR 形式交付。

## 目标形态

- 对外只交付一个主 AAR：`hardware-scale`
- 对外根包固定为 `com.holderzone.hardware.scale`
- 每次调用 `ScaleFacadeFactory.create(context)` 都返回一个新的 facade 实例
- 支持 `Auto` 自动探测和显式指定厂商
- 不再暴露全局 `WeightManager` 单例
- 厂商二进制不再由主模块直接 `fileTree("*.aar")` 引入

## 目录结构

```text
hardware/scale
  src/main/java/com/holderzone/hardware/scale
    core
    driver/jw
    driver/ly
    driver/sohe
    internal
  src/main/AndroidManifest.xml
  libs/jw
```

说明：

- `src/main/java/com/holderzone/hardware/scale`
  现在是单 module 收口后的唯一源码入口
- `core`
  `DefaultScaleFacade`、厂商选择、生命周期串行化和 driver 桥接
- `driver/jw`
  JW 厂商 SDK 适配，同时收口 JW 厂商二进制
- `driver/ly`
  亮悦串口协议适配
- `driver/sohe`
  首衡 13 字节 ASCII 串口协议适配
- `libs/jw`
  当前 module 自己持有的 JW 厂商二进制
- 共享串口底层二进制
  已收口到仓库内部模块 `:hardware:vendor`，对业务侧不需要单独感知

## 对外 API

核心入口：

```kotlin
val facade = ScaleFacadeFactory.create(context)

val result = facade.start(
    ScaleConfig(
        vendorPreference = ScaleVendorPreference.Auto,
        vendorHints = ScaleVendorHints(
            jwUseSecondaryScreen = false,
        ),
        logger = ScaleLogger.None,
    )
)
```

门面能力：

```kotlin
interface ScaleFacade : Closeable {
    val state: StateFlow<ScaleState>
    val events: Flow<ScaleEvent>
    val capabilities: StateFlow<ScaleCapabilities>
    val readings: Flow<WeightReading>

    suspend fun start(config: ScaleConfig): ScaleResult<Unit>
    suspend fun readOnce(): ScaleResult<WeightReading>
    suspend fun tare(): ScaleResult<Unit>
    suspend fun zero(): ScaleResult<Unit>
    suspend fun stop(): ScaleResult<Unit>
    override fun close()
}
```

关键模型：

- `ScaleConfig`
- `ScalePortConfig`
- `ScaleVendorHints`
- `ScaleVendor`
- `ScaleVendorPreference`
- `ScaleCapabilities`
- `ScaleState`
- `ScaleEvent`
- `ScaleResult`
- `ScaleError`
- `WeightReading`
- `ScaleLogger`

## 厂商支持矩阵

| Vendor | Stream Weight | Read Once | Tare | Zero |
| --- | --- | --- | --- | --- |
| `JW` | Yes | Yes | Yes | Yes |
| `LY` | Yes | Yes | Yes | Yes |
| `SOHE` | Yes | Yes | Yes | Yes |

说明：

- 自动探测顺序固定为 `JW -> LY -> SOHE`
- 自动探测会先按厂商顺序，再按该厂商内置串口候选表顺序尝试
- 自动探测依赖在 `probeTimeoutMs` 内收到有效重量数据
- 现场设备如果输出节奏慢，建议优先使用显式指定厂商
- 如果现场机器做过特殊改线，可以通过 `portOverride` 手动覆盖默认串口
- 首衡未预置默认串口候选，建议显式指定 `ScaleVendor.SOHE` 和 `portOverride`

## 自动探测与显式指定

自动探测：

```kotlin
facade.start(
    ScaleConfig(
        vendorPreference = ScaleVendorPreference.Auto,
    )
)
```

显式指定：

```kotlin
facade.start(
    ScaleConfig(
        vendorPreference = ScaleVendorPreference.Explicit(ScaleVendor.JW),
        vendorHints = ScaleVendorHints(
            jwUseSecondaryScreen = true,
        ),
    )
)
```

手动覆盖串口：

```kotlin
facade.start(
    ScaleConfig(
        vendorPreference = ScaleVendorPreference.Explicit(ScaleVendor.JW),
        portOverride = ScalePortConfig("/dev/ttyS9", 115200),
    )
)
```

当前 SDK 内置默认候选串口：

- `JW`：`/dev/ttyS8` -> `/dev/ttyS1` -> `/dev/ttyS2` -> `/dev/ttyS3`，默认波特率 `9600`
- `LY`：`/dev/ttyS1` -> `/dev/ttyS3` -> `/dev/ttyS2`，默认波特率 `9600`
- `SOHE`：无默认候选，请通过 `portOverride` 传入现场串口和波特率

## 读取、去皮、清零

读取一次当前重量：

```kotlin
when (val result = facade.readOnce()) {
    is ScaleResult.Ok -> {
        val reading = result.value
    }
    is ScaleResult.Err -> Unit
}
```

订阅实时重量：

```kotlin
facade.readings.collect { reading ->
    // update ui
}
```

去皮与清零：

```kotlin
facade.tare()
facade.zero()
```

首衡第一版适配按协议默认的连续发送模式工作。`R` 主动读取、`S` 获取自定义皮重和
`YARE` 设置自定义皮重暂未加入公共 API；首衡返回的 `t` 皮重帧会被驱动识别并记录，
不会进入实时重量流。

## AAR 打包命令

在仓库根目录执行：

```powershell
.\gradlew.bat :hardware:scale:assembleRelease
.\gradlew.bat assembleScaleSdkRelease
.\gradlew.bat printScaleSdkVersion
.\gradlew.bat cleanLocalMavenRepo publishFoundationToLocalRepo
```

默认产物位置：

```text
hardware/scale/build/outputs/aar/hardware-scale-<version>-release.aar
```

## 从旧 WeightManager 迁移

旧入口与新入口映射：

| Old | New |
| --- | --- |
| `WeightManager.initialize(...)` | `ScaleFacadeFactory.create(context).start(config)` |
| `WeightManager.tare()` | `facade.tare()` |
| `WeightManager.zero()` | `facade.zero()` |
| `WeightManager.release()` | `facade.stop()` / `facade.close()` |
| `WeightCallback.onWeightChanged(...)` | `facade.readings` / `ScaleEvent.WeightUpdated` |
| `WeightVendorType` | `ScaleVendor` / `ScaleVendorPreference` |

注意：

- 新 SDK 不再暴露全局 `WeightManager`
- 新 SDK 不再依赖静态 `WeightUtils`
- 新 SDK 统一通过 `ScaleError` 暴露错误，不再只给裸字符串
