# 内存泄漏审计与修复记录（LiquidGlassWatermark）

审计时间：2026-10-18
审计范围：`app/src/main/java/com/liquidglass/watermark` 全部源码（UI / 渲染 / 导出 / 媒体管线），
重点：位图生命周期、协程并发、渲染 scratch 分配、MediaCodec/MediaExtractor/MediaMuxer 释放、
Compose 组合生命周期、静态缓存。

---

## 一、已修复的问题

### F1. 快速拖动滑块时的并发渲染堆积（高 · 真实内存峰值）

**位置**：`ui/WatermarkViewModel.kt` → `renderStrip()`

**问题**：拖动滑块会以 110ms 防抖高频调用 `renderStrip`。每次调用先 `stripJob?.cancel()` 再起新协程，
但 `GlassBadgeRenderer.renderOnto()` 是纯 CPU 阻塞调用、**没有挂起点**——被取消的协程不会停止已经在跑的渲染，
而是跑完才退出。结果：快速拖动时多个 720px 渲染并发执行，每个渲染同时分配
badge Bitmap + backdrop `IntArray` + lens `IntArray` + blur scratch，内存与 CPU 同时堆积。

**修复**：新增 `renderMutex = Mutex()`，所有 strip 渲染在锁内串行执行；等待中的协程可被取消，
新请求会挤掉排队中的旧渲染。现在任意时刻最多一个渲染在跑。

### F2. 快速连续选择媒体时的并发覆盖（高 · 真实泄漏路径）

**位置**：`ui/WatermarkViewModel.kt` → `selectMedia()`

**问题**：
1. 快速连选多张图时，多个 `selectMedia` 协程并发：probe + 解码 + 渲染互不取消，
   后面的选择直接覆盖 `baseFrame`，前面的渲染协程还在读旧帧。
2. 旧 `baseFrame` 被替换时从不 `recycle()`，只靠 GC 兜底。

**修复**：
- 新增 `selectJob`，新选择先取消旧选择，探测/解码管线有界为一。
- `baseFrame` 的替换与回收放进 `renderMutex` 锁内，与 strip 渲染串行——
  锁保证了"旧帧不再被任何渲染读取"后才 `recycle()`，杜绝 `Canvas` 画已回收位图。
- `onCleared()` 同步回收 `baseFrame`。

### F3. 视频导出每帧分配 3~4 个数组/字体对象（中 · GC 压力，非泄漏但拖慢导出）

**位置**：
- `core/render/GlassBadgeRenderer.kt` → `buildBadgeBitmap()` / `sampleBackdrop()` / `configurePaints()`
- `core/render/BoxBlur.kt` → `blur()`
- `core/render/LiquidLens.kt` → `refract()`

**问题**：`renderOnto` 每视频帧执行一次，每次：
- `sampleBackdrop` 新分配 backdrop `IntArray`；
- `BoxBlur.blur` 新分配 scratch `IntArray`（3 次 pass 复用同一个）；
- `LiquidLens.refract` 新分配 lens 输出 `IntArray`；
- `configurePaints` 每帧调用 `Typeface.create()` 3 次（每次创建对象）。

1080p 视频 600 帧 → 上千次数组分配 + 1800 次 Typeface 创建，全部变成短期垃圾，
拖慢导出并放大 GC 停顿。

**修复**：
- `GlassBadgeRenderer` 持有 `scratchBackdrop / scratchLensed / blurScratch` 三个按需扩容的复用缓冲
  （渲染器单线程使用，安全）；`release()` 释放。
- `BoxBlur.blur` / `LiquidLens.refract` 增加可选 `scratch` / `out` 参数，缺省行为不变
  （纯函数签名向后兼容，单测不受影响）。
- `Typeface` 实例缓存为渲染器字段，每渲染器只创建一次。

### F4. GIF 导出全帧收集的 138MB 峰值（高 · OOM 风险）

**位置**：`core/export/GifExporter.kt` → `extractFramesArgb()` + `GifFrames`

**问题**：GIF 路径先解码全部 150 帧、每帧 `getPixels` 拷贝成 `IntArray` 存进 `ArrayList`，
再统一喂给 `GifEncoder`。480×480 帧 = 921KB/帧 × 150 ≈ **138MB 常驻**，
加上 `GifEncoder` 输出的 `ByteArrayOutputStream`，峰值逼近小内存设备的 OOM 线。

**修复**：改为流式编码 `encodeFramesStreaming()`——逐帧解码 → 逐帧 `addFrame` 进 `GifEncoder`
（其内部 `ByteArrayOutputStream` 边写边扩），`GifFramePlan` 仍先行算出采样计划。
峰值内存从"全帧列表 + 输出"降到"单帧 + 输出"。删除 `GifFrames` 收集类；
`planFrames / frameSampleTimesUs / gifFrameCount / scaledToMaxWidth / frameDelayCentis`
等纯函数签名未变，`GifExporterTest` 全部保留。

---

## 二、审查通过（有意设计，非泄漏）

| 项目 | 结论 |
|---|---|
| `GlassMaterials.grainCache / bitmapCache` 静态缓存 | 上限 8 张 128×128 纹理（≈512KB），`clearCache` 只清引用不 `recycle` 是**有意为之**：另一个渲染线程可能正在用 `BitmapShader` 采样，显式回收会造成 use-after-recycle。有界 + 不回收 = 安全设计。 |
| `VideoExporter` 编解码管线 | `DecodePipeline` 的 codec/ImageReader 在 `close()` 释放；encoder surface 在 `VideoTrackEncoder.close()` 释放；`HandlerThread` 每次 `quitSafely()`；extractor/muxer 在 `finally` 释放；旋转/缩放位图逐级 `recycle`。资源管理完备。 |
| `rememberVideoPlayer` / `rememberIsPlaying` | ExoPlayer 由 `DisposableEffect` 释放，切换 uri 时旧实例立即 `release()`；`Player.Listener` 正确移除。 |
| `strip` ImageBitmap 旧值 | `asImageBitmap()` 是包装非拷贝，旧 strip 交给 GC（API 26+ 像素在 Java heap），串行化后数量有界。 |
| `PhotoExporter` 动态照片路径 | `readBytes` + `parse` 双 `copyOfRange` + 输出数组峰值约 3× 文件大小（30MB 动态照片 ≈ 100MB 峰值）。**已知峰值风险**（瞬态，非泄漏），4GB 设备可承受；已列入后续优化清单（流式写 MediaStore）。 |
| 时钟协程 | `startClock/stopClock` 由 `DisposableEffect` 管理，后台不空转。 |

---

## 三、遗留风险与后续优化

1. **动态照片导出峰值内存**：改为 `MotionPhoto.withMotionXmp()` 独立插入 XMP + 流式顺序写
   （jpeg + video 直接写 MediaStore OutputStream），可把峰值从 3× 降到 1.5× 文件大小。
2. **`VideoExporter` 单帧峰值**：解码帧 + 旋转帧 + 输出帧 + badge 位图同时存活约 30-40MB，
   可通过复用旋转 scratch 位图进一步降低（当前已受 F3 收益）。
3. **`onTrimMemory` 接入**：`MainActivity` 可监听 `ComponentCallbacks2.onTrimMemory` 调用
   `viewModel` 的渲染器 `release()`，释放静态纹理缓存。

---

## 四、验证方式

- 单元测试：`./gradlew :app:testDebugUnitTest`（GifExporter/GifEncoder 纯函数测试全绿）。
- 构建：`./gradlew :app:assembleDebug`。
- 手动回归（建议真机/模拟器）：反复快速拖动"扭曲/磨砂/高光"滑块观察流畅度；
  快速连选 5 张以上图片；导出长视频（检查导出期间内存曲线平稳）；导出 GIF 动画。
- 工具：Android Studio Profiler → Memory，检查拖滑块/连选素材后堆内存是否回落
  （修复前：快速拖动期间堆曲线阶梯式上升且回落缓慢；修复后：单峰瞬时、立即回落）。
