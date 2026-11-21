# LiquidGlass Watermark · 液态玻璃水印 v15.40

Android 图片 / 视频水印编辑器。Kotlin + Jetpack Compose，底部导航为 **KernelSU 管理器真源码移植的液态玻璃悬浮 Dock**。

- 包名：`com.liquidglass.watermark`（debug 包为 `com.liquidglass.watermark.debug`）
- 版本：**versionCode 1540 / versionName 15.40**
- 平台：minSdk 26 / targetSdk 37 / compileSdk 37，JVM 17

---

## 1. 本版更新（v15.40）

### 1.1 Dock 缩短后胶囊不再变圆（保持 KSU 形态）

| 现象 | 根因 | 修复 |
|---|---|---|
| Dock 精简为 5 项后，胶囊在视觉上变成椭圆 / 圆形 | 网格底层用的是 `CircleShape`（圆角 50%，`rx` 按**宽度**算）。Dock 原本接近满宽，`rx≈165dp` 而高只有 52dp，弧线极浅，看着像胶囊；一旦缩短，`rx` 也跟着缩，上下两条边就整条变弧，形状退化成椭圆。KSU 原版之所以没问题，只是因为它一直近满宽 | 胶囊主体改用 `RoundedCornerShape(pillH / 2)` —— **半径只跟高度走**（固定 26dp）。上下边是直线、两端是半圆，宽度再怎么短也仍是胶囊 |
| 选中指示片（玻璃镜片）同样被缩成椭圆，左端还会和胶囊端弧糊在一起像一坨圆 | 指示片也用的 `CircleShape`，`rx` 随格子宽度变化 | 指示片改为 `RoundedCornerShape(lensH / 2)`，并令 `lensH = pillH − 16dp`；同时在胶囊内部左右留白 4dp → **10dp**，首尾格的图标与镜片完全落在直线段内，不再压到端弧 |

实测（1080×2340 模拟器，5 项 Dock ≈ 330dp）：胶囊上下沿平直、两端为半圆，选中镜片独立成片、四周留出余量，与 KSU 的悬浮玻璃形态一致；拖动/点选、镜片跟随位移与按压回弹逻辑均未改动。

### 1.2 顺带确认上一版的两项主修复仍然有效

- **字体切换**：默认字体 = 出厂字库（`NotoSansCJK-Regular.ttc`，窄体）、系统字体 = 当前生效字体（Roboto，宽体），逐帧 `applyTypeface` 同步，切换即时可见。
- **Dock 布局**：宽度 = 项数 × 62dp + 22dp（5 项 ≈ 330dp，封顶 560dp），水平居中，底距 22dp。

## 2. v15.39 更新

### 2.1 字体切换彻底修好（真正的切换，不再"时灵时不灵"）

| 问题 | 根因 | 修复 |
|---|---|---|
| 选「默认字体」没有变化 / 选「系统字体」也没有变化 / 有时任何字体都不生效 | ① 出厂字库写死 5 个文件名，很多 ROM 上名字对不上 → `loadFactory` 一路回落到 `SANS_SERIF`，而它早被主题字体接管，于是「默认」=「系统」；② typeface 只在 `Ctx` 创建时设一次，靠"模式变了就重建 Ctx"兜底，导出线程快照 / 方案切换 / 提前重绘任一处不同步就拿到旧 Paint | ① 改为**扫描 `/system/fonts` 真实文件**，按「CJK 优先 → 拉丁兜底」挑选，逐个试加载并用"能否渲染出字形"校验（坏字库直接丢弃），全部失败才回落并如实标注；② 新增 `applyTypeface()`，在 `measure()` 与 `drawContent()` **逐帧**比对并同步 `titlePaint/subPaint` 的 typeface |
| 「主题替换」状态误报（连 AOSP 原生机都显示"已被主题替换"） | 拿「出厂 CJK 字库」直接和 `Typeface.DEFAULT` 比：中英混排时 DEFAULT 的拉丁是 Roboto、汉字才走 CJK，永远不相等 | 按字符集分开判定：拉丁样本对 `Roboto/Droid`、汉字样本对 CJK 字库；文案改为中性的「系统字体与出厂字体不同 / 系统字体=出厂字体」 |

### 2.2 Dock 改为紧凑居中、整体上移

- 宽度：按 **项数 × 62dp + 22dp** 计算（5 项 ≈ 330dp），超宽屏封顶 560dp。
- 位置：底距 22dp，悬浮更高（有导航栏机型再叠加 inset）。
- 高度沿用屏宽 15.5%（46~58dp 夹取）。

## 3. v15.38 要点

- Dock 由 6 项精简为 5 项：形状 / 玻璃 / 文字 / 位置 / 设置；原「光泽」并入「玻璃」页内子页签，功能零丢失。
- 相册一次多选配对动态照片（`ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE`）。
- 方案2 型号上下 / 位置行排序开关 / 分隔竖线溢出 / 上下滑块量程 / Dock 跨机型自适应等修复。

## 4. 已知问题与限制

- 主题字体判定依赖字形比对，vivo / OPPO / 小米各代主题字库未逐一真机验证；文案已改为中性描述。
- 「真实地名」需定位权限；无权限或模拟器无地理数据时该行不绘制。
- 超大字号（>100sp 且文案长）标题可能横向超出玻璃条，仅保证纵向不溢出。
- `app/src/test/java/...core/**` 引用不存在的 `core.export` 包，`testDebugUnitTest` 无法编译；不影响 assemble。
- 平板横向布局未专门适配（Dock 封顶 560dp 居中）。
- 导出栏（MP4 / HEVC / GIF / 模式 / 动照）在窄屏上需横向滑动，最右侧按钮默认在可视区外。

## 5. 安装

下载 `LiquidGlassWatermark_v15.40_release.apk`（Release 附件）直接覆盖安装（同签名）。

## 6. 从源码构建

```bash
./gradlew :app:assembleDebug     # 调试包，applicationId 带 .debug 后缀
./gradlew :app:assembleRelease   # 正式包（需 keystore.properties）
```

`keystore.properties` 不入库，参考 `keystore.properties.example` 自建。

## 7. 版本信息

| 项 | 值 |
|---|---|
| versionCode / versionName | 1540 / 15.40 |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37 |
| 签名 | V3，证书 SHA-256 `22bd1ff1…04a439` |
| 渲染入口 | `WatermarkRenderer.kt`（CLASSIC / SINGLE / STACK / SPLIT） |
| 字体 | `FontSupport.kt` + `resolveTypeface` + `applyTypeface` |
| Dock | `ui/ksu/FloatingBottomBar.kt`（`barShape` / `pillShape` / `DockInnerPad`） |

## 8. 历史版本

- **v15.40** — Dock 胶囊改为固定半径（半径随高度、不随宽度），缩短后仍是胶囊；指示片同步改形并加内衬。
- **v15.39** — 字体切换彻底修复（扫真实字库 + 逐帧同步 typeface）；Dock 紧凑居中上移。
- **v15.38** — Dock 精简为 5 项（玻璃 + 光泽合并）；多选配对、竖线溢出、位置行排序、滑块量程修复。
- **v15.37** — 真机实测 bug 修复；v15.36 Dock 宽度自适应；v15.35 Dock 压缩；v15.34 KSU Dock 真源码移植；v15.28~33 iOS27 通道 / 流体呼吸 / 高级光学 / 四行独立字号。

## 9. 许可

见 `LICENSE` 与 `NOTICE.md`。
