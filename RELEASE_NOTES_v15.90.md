# Liquid Glass Watermark v15.90 发布说明

**发布日期**：2026-11-28
**版本标识**：versionName `15.90` / versionCode `1590`
**基线**：v15.80（按用户截图 185927 还原），在其上仅修复胶囊显示的两个问题
**仓库**：https://github.com/fool593/LiquidGlassWatermark
**下载站**：https://fool593.github.io/LiquidGlassWatermark/

---

## 一、本次版本（v15.90）修复的问题

### 1.1 静止胶囊过于扁平、上下留白过大

**现象**：未放大状态下，Dock 选中胶囊高度只有 `canvasH × 0.60`（约 46dp），而 Dock 本体约 63dp，胶囊上下各留约 8dp 空隙，观感偏扁、不贴边。

**修复**（`ui/ksu/FloatingBottomBar.kt`）：

```kotlin
// 原
.fillMaxHeight(0.6000f + 0.1800f * pressProgress)
// 新
.fillMaxHeight(0.8600f + 0.0400f * pressProgress)
```

`canvasH = pillH + 14dp`，静止高度变为 `0.86 × (pillH+14) × scaleY(0.955) ≈ pillH`，胶囊上下边缘基本贴合 Dock 边缘；放大系数 0.90 仍在 canvas 的 1.0 上界之内，不会越出采样节点。

### 1.2 放大状态下胶囊向右越界、覆盖相邻 tab 文字

**现象**：按住放大时胶囊向右延伸，压住右侧 tab 的文字标签。

**根因**（两处叠加）：

| 项 | 原值 | 后果 |
|---|---|---|
| `lensWpx` | `tabWidthPx × (1.28 + 0.47 × p)` | 放大时定位宽度涨到 1.75 cellWidth，首 tab 的 `rawLeft` 被 `coerceIn` 夹住，胶囊中心被向内推移约 0.18 cellWidth |
| 可见宽度 | `fillMaxWidth(0.64 + 0.17 × p)` | 放大到 1.58 cellWidth，半宽 0.79 cellWidth；叠加中心右移后右缘约 1.51 cellWidth，超过相邻 tab 文字左缘约 1.32 cellWidth |

**修复**：

```kotlin
// 定位宽度恒定，不随按压变化
val lensWpx = tabWidthPx * 1.28f

// 放大宽度上限 0.70 canvas = 1.365 cellWidth
.fillMaxWidth(0.6400f + 0.0600f * pressProgress)
```

修复后胶囊中心恒为 0.5 cellWidth（tab 中心），放大右缘约 1.22 cellWidth，小于相邻文字左缘约 1.32 cellWidth，留出约 0.1 cellWidth（约 6dp）间距。放大感改由 `scaleX / scaleY` 与高度增量承担，不再靠横向外扩。

---

## 二、本版本包含的新增与变更功能（相对 v15.73）

v15.90 的源码基线是 v15.80，相对站点上一版 v15.73 包含以下 Dock 与交互演进：

1. **Dock 指示器尺寸修正**（v15.80）：指示器宽 `0.64 → 1.248 cellWidth`、高 `0.60 → 0.60 canvas` 的显式比例，四 tab 等距排列，设置面板高度改为 `screenHeight × 0.34`（clamp 240~340dp），短屏不再只露 80dp。
2. **背景采样源隔离**（v15.83）：Dock 只采样纯背景 scene，不再把设置面板的蓝色 chip 一起采样，消除穿过图标的蓝色横条。
3. **图标 Canvas 固定方形**（v15.80 起）：`KsuDockIcon` 使用固定 28dp 方形 Canvas，避免被父约束压成横线。
4. **显式几何与可见高光**（v15.82~v15.86 的保留项）：指示器几何用显式像素计算，`highlight = null` 后用 `SrcOver` 绘制白色上弧、深蓝灰下弧与 RGB 色散，近白/近黑背景上高光可见；低版本不依赖 AGSL/RenderEffect 也能保留折射与色散。
5. **本次两项修复**（v15.90）：静止胶囊贴边、放大不越界。

**保持不变的能力**：照片水印（4 布局 / 14 品牌 Logo / 自定义 Logo）、视频水印（GPU 硬编 + 逐帧兜底）、动态照片（vivo 双文件 + Google XMP，5 秒截取）、液态玻璃光学（折射 / 内部放大 / 色散 / 菲涅尔边缘光 / 高光，全参数可调）、三语界面、字体切换、离屏层缓存与重组收敛。

**明确未改动**：颜色、模糊、折射、色散、高光、圆角形状、tab 数量与图标文字、点击/拖拽手势、面板结构。

---

## 三、构建与校验

| 项 | 值 |
|---|---|
| 构建命令 | `gradlew -p <工程目录> assembleRelease assembleDebug --console=plain` |
| 结果 | `BUILD SUCCESSFUL in 3m 24s`（79 tasks：22 executed、57 up-to-date） |
| release | `com.liquidglass.watermark`，versionCode `1590`，versionName `15.90`，minSdk 26 / targetSdk 37 |
| debug | `com.liquidglass.watermark.debug`，versionCode `1590`，versionName `15.90` |
| 签名 | DN `CN=Liquid Glass Watermark...`；SHA-256 `6e7d65de49738b6e0bb4657305673fc628a15220250767129054daecdf81247b` |
| 产物 | 稳定版 APK 15,031,384 B；测试版 APK 19,792,688 B；源码快照 ZIP（174 文件） |

---

## 四、下载

- [稳定版 APK（v15.90）](https://github.com/fool593/LiquidGlassWatermark/releases/download/v15.90/app-release.apk)
- [测试版 APK（v15.90 debug）](https://github.com/fool593/LiquidGlassWatermark/releases/download/v15.90/app-debug.apk)
- [源码包（v15.90）](https://github.com/fool593/LiquidGlassWatermark/releases/download/v15.90/LiquidGlassWatermark_v15.90_source.zip)
- [GitHub Release v15.90](https://github.com/fool593/LiquidGlassWatermark/releases/tag/v15.90)
