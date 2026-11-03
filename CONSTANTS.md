# 项目常数（CONSTANTS）

从源码直接提取的项目关键常数，版本对齐：v15.16（`versionCode 1516`）。

## 构建 / 环境

| 常数 | 值 | 位置 |
|---|---|---|
| `applicationId` | `com.liquidglass.watermark` | `app/build.gradle.kts` |
| `compileSdk` / `targetSdk` | 34 | 同上 |
| `minSdk` | 26 | 同上 |
| `versionCode` | 1516 | 同上 |
| `versionName` | `"15.16"` | 同上 |

## 玻璃光学（LiquidGlassEngine.kt — v15.15 基线系数，恢复版）

| 光学量 | 公式 / 系数 | 说明 |
|---|---|---|
| 折射带宽度 `band` | `max(1.5f * comp, shortSide * (.035f + refractHeight * .085f) * comp)` | 边缘厚度，下限 1.5px |
| 折射位移 `lensAmplitude` | `band * refractAmount * .78f` | 位移幅度系数 0.78 |
| 内部放大 `magnification` | `innerWarp * .065f (+ depth 时 refract * .018f)` | 法线 UV 折射向内拉 |
| 色散 `dispersion` | `shortSide * .009f * chromatic` | 色散带系数 0.009 |
| 镜面高光 `specular` | `.018f + .030f * v` | 边缘高光随 v 增长 |
| 遮蔽 `shade` | `1f - innerShadow * edge * (.035f + .17f * backlit)` | 内阴影遮蔽 |
| 缩放补偿参考 `SCALE_REFERENCE_PX` | `220f` | 短边 ≥220px 补偿=1；小于则 `(220/side)^0.45`，上限 ×3.2 |
| 画质档 `TIER_HIGH / TIER_LOW` | `0 / 1` | High=3 抽样色散+pow 菲涅尔；Low=法线 UV 折射保留+廉价近似 |
| 光照角 `lightAngle` | `.7853982f` (π/4) | 默认光源方向 |

## 渲染性能（WatermarkPerformancePolicy.kt）

| 常数 | 值 | 说明 |
|---|---|---|
| `previewEdge` / `animatedPreviewEdge` | 480 | 预览最大边像素 |
| `videoFrameEdge` | 480 | 视频帧水印最大边像素 |
| `exportPatchEdge` | 480 | 导出 patch 最大边像素 |
| `animationIntervalMs` | 40L（受限设备 50L） | 预览动画调度间隔 |

## 导出管线（Mp4Exporter / GpuMp4Exporter / MotionDecoder.kt）

| 常数 | 值 | 说明 |
|---|---|---|
| 解码预算 `decodeBudgetMs` | `remainFrames * 625L / workers + 90_000L` | 双 worker 解码软超时 |
| `decodeTimeoutMs` | `max(300_000, durMs*6 + frames*150)`，上限 2h | 源解码超时 |
| GPU 导出 `deadline` | `max(10 * 60_000, durMs * 8)` | 总截止时间 |
| 动态照片截断 | `>5_000ms 截取前 5s` | `composeMotionPhoto` 判定 + 编码器 PTS 截断 |
| 帧队列 `surfaceFrameQueue` | `LinkedBlockingQueue(8)` | 解码双 worker 背压 |
| 输出完整性容差 | `max(250, 2000/fps) ms` | 封装时长期望（截断时取 `min(源, maxDurationMs)`） |

## 状态 / 参数（EditorState.kt）

| 常数 | 值 | 说明 |
|---|---|---|
| `PREFS` | `"lgw_prefs"` | 偏好文件 |
| `PARAMS_VERSION` | 6 | 参数版本 |
| `PLAN_VERSION` | 5 | 方案版本 |
| 形状 `SHAPE_*` | Rounded=0 / Capsule=1 / Ellipse=2 / Rect=3 | 玻璃形状 |
| 布局 `LAYOUT_*` | Classic=0 / Single=1 / Stack=2 / Split=3 | 水印排版 |
| 品牌 `BRAND_*` | Leica=0 … OnePlus=14，`BRAND_NONE=-1` | Logo 类型 |
| 字体 `FONT_MODE_DEFAULT/SYSTEM` | 0 / 1 | 渲染字体模式 |
| `GLASS_TIER_HIGH/LOW` | 0 / 1 | 画质档（同步 GlassQuality.tier） |

## 字体（WatermarkRenderer.kt —— 官方字体修复区）

| 常数 | 值 | 说明 |
|---|---|---|
| 内置字体 | `assets/fonts/NotoSansSC-Regular.otf` / `-Bold.otf` | 默认中文字体（加载失败回退 sans-serif） |
| 系统字体源 | `/system/etc/fonts.xml` → `/system/fonts/fonts.xml` | sans-serif 族 weight=400 非斜体 |

## 动照片 / 输出（MotionPhotoWriter.kt）

| 常数 | 值 | 说明 |
|---|---|---|
| 格式 | `motionPhotoFormat 0` = vivo 双文件（`.MP.jpg`）`1` = Google XMP | 输出封装 |
| 封面编码 | JPEG quality 95 / 92（motioncut 首帧） | 封面压缩 |
| 5s 判定 | `vDur > 5000L` → `motionMaxMs = 5000L` | 主预览 uri > 主预览字节 > 手动面板 uri |

*提取自 v15.16 光线恢复版源码（LiquidGlassWatermark 仓库 @ main）。*