# LiquidGlass Watermark · 液态玻璃水印 v15.43

Android 图片 / 视频水印编辑器。Kotlin + Jetpack Compose，底部导航为 **KernelSU 管理器真源码移植的液态玻璃悬浮 Dock**。

- 包名：`com.liquidglass.watermark`（debug 包为 `com.liquidglass.watermark.debug`）
- 版本：**versionCode 1543 / versionName 15.43**
- 平台：minSdk 26 / targetSdk 37 / compileSdk 37，JVM 17

---

## 1. 本版更新（v15.43）

### 1.1 Dock 玻璃参数体系（设置页可折叠）

「设置 → Dock 玻璃参数」一行折叠条，点开是 6 个滑块，全部实时生效、松手落盘（全局键，不随方案切换）：

| 滑块 | 作用 | 0 → 100 |
|---|---|---|
| 磨砂厚度 | 表面奶白浓度 + 磨砂渐变 | 几乎全透 → 厚奶玻璃 |
| 背后模糊 | 取样层的模糊半径 | 后面完全清晰 → 24dp 重糊（iOS 那种只剩色块） |
| 边缘折射高度 | 折射斜面厚度 | 平边 → 20dp 斜面 |
| 边缘折射位移 | 边缘把背后内容弯折多少 | 0 → 18dp（约 36% 放大率） |
| 玻璃染色 | 填充色浓度系数 | 0.55× → 1.55× |
| 边缘高光 | 顶部细白边强度 | 0.06 → 0.46 |

默认值（40 / 50 / 45 / 55 / 35 / 50）即 iOS 观感档。

### 1.2 滑块「一拖就弹回」修复

根因：Dock 滑块 `onChange` 只调了 `bumpDockGlass()`（递增 `dockGlassRev`），而 `SettingsTab` 只读 `act.rev`，**没有订阅这个令牌** → 面板重组作用域不失效 → `IntSlider` 的 `value` 参数仍是旧值 → 松手/点击后 `LiquidSlider` 回读旧值，视觉上就是弹回原位、参数没保存。现在 `SettingsTab` 首行同时读 `EditorState.dockGlassRev`，滑块拖动与点击跳转都能正确落到持久化值。

### 1.3 启动不再反复弹崩溃日志

`App.showCrashLog` 之前只读 `files/crash/last.txt` 但从不清理，只要历史崩溃过一次，之后每次冷启动都会弹同一条 Toast。现在提示一次后立即删除该文件。

### 1.4 低版本同样是「真模糊」

`PrepareSoftwareGlassBackdropLive` 每 0.45s 抓一帧参数面板图层 → 降采样到 480px → FastBlur → 写回 `LayerBackdrop.softwareImage`。没有 RenderEffect 的机型（Android 11 及以下 / 模拟器）走的就是这条软件模糊 + 软件折射（`ScaleLens` 的原始失真缺陷同时被修掉：`DrawBackdropModifier.updateEffects()` 在无 RenderEffect 时也会跑 `effects{}` 拿到 `softwareLens`）。

## 2. v15.42 要点

- 底部悬浮层不铺任何底板，参数面板一直铺到屏幕底；胶囊填充 0.05、高光 0.4；按住重影三来源（隐形镜像层 / 整条方角高光 / 按压缩放）全部移除。
- 分层重构：玻璃 → 镜片 → 内容，内容永远画在镜片之上。

## 4. 已知问题与限制

- 面板内容与导出栏、Dock 是重叠的（悬浮层无底板），静止时贴近底部的行会压在导出栏下面，下滑即可看全。
- 底部玻璃只有 Dock 胶囊一层，低端机开销可忽略；胶囊内部模糊会让后面的选项略微柔化（玻璃本身特性）。
- 主题字体判定依赖字形比对，vivo / OPPO / 小米各代主题字库未逐一真机验证。
- 「真实地名」需定位权限；无权限或模拟器无地理数据时该行不绘制。
- 超大字号（>100sp 且文案长）标题可能横向超出玻璃条，仅保证纵向不溢出。
- `app/src/test/**` 引用不存在的 `core.export` 包，`testDebugUnitTest` 无法编译，不影响 assemble。
- 平板横向布局未专门适配（Dock 封顶 560dp 居中）。
- 导出栏在窄屏上需横向滑动，最右侧按钮默认在可视区外。

## 5. 安装

下载 `LiquidGlassWatermark_v15.42_release.apk`（Release 附件）直接覆盖安装（同签名）。

## 6. 从源码构建

```bash
./gradlew :app:assembleDebug     # 调试包，applicationId 带 .debug 后缀
./gradlew :app:assembleRelease   # 正式包（需 keystore.properties）
```

## 7. 版本信息

| 项 | 值 |
|---|---|
| versionCode / versionName | 1541 / 15.41 |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37 |
| 签名 | V3，证书 SHA-256 `22bd1ff1…04a439` |
| Dock | `ui/ksu/FloatingBottomBar.kt`（barShape / pillShape / DockInnerPad / DockAccent） |
| 底部悬浮层 | `ui/EditorScreen.kt`（EditorContent 的 shelf 玻璃层） |

## 8. 历史版本

- **v15.41** — Dock 真正悬浮玻璃化（面板穿过 Dock、整条栏玻璃、胶囊 0.14 透明）+ 按住重影修复 + 三层分层。
- **v15.40** — Dock 胶囊固定半径，缩短后不再是椭圆。
- **v15.39** — 字体切换彻底修复；Dock 紧凑居中上移。
- **v15.38** — Dock 精简为 5 项（玻璃 + 光泽合并）；多选配对、竖线溢出、位置行排序、滑块量程修复。

## 9. 许可

见 `LICENSE` 与 `NOTICE.md`。
