# Liquid Glass Watermark（液态玻璃水印）

在照片 / 视频上叠加带有真实光线折射与扭曲的「液态玻璃」水印的 Android 编辑器。

> 版本：15.17（`versionCode 1517`） · 包名：`com.liquidglass.watermark`
> 环境：minSdk 26 / target & compile SDK 34 · Kotlin + Jetpack Compose + AGSL（Android 13+）

---

## 官方网站与下载 / Official Site & Download

> 🔗 官方网站（效果图 / 更新日志 / 扫码下载）：**https://fool593.github.io/LiquidGlassWatermark/**

> 🧊 **一句话介绍** — 这层玻璃是真的：背景透过水印会弯曲，像透过一块真正的水滴透镜看世界，不是贴图。

> 🎯 **项目目标** — 每一台安卓手机都能摸到真正的「液态玻璃」：低版本不降级成磨砂，高版本用满光学。

> 🔒 **隐私承诺** — 不联网 · 照片不上传 · 免注册 · 无广告，水印全在本机处理。

| Release 包（v15.17） | 说明 | 适合谁 |
|---|---|---|
| **修复版（推荐）** — `LiquidGlassWatermark-v15.17-romguard.apk` | 新增 ROM 兼容防护：修复小米/红米/POCO 澎湃系统（Android 13+）打开即闪退的问题（已在澎湃真机验证不再闪退）；其余手机默认全量光学，体验不变 | 所有新装用户（尤其澎湃/HyperOS 用户） |
| 上一版本 v15.16 · `*export-speed-restored.apk` | 视频导出速度最顺滑（恢复旧版光线管线后） | 经常导出长视频 |
| 上一版本 v15.15 · `*UI-performance.apk` | 编辑界面滑动更跟手（UI 渲染层优化） | 更在意编辑手感 |

v15.17 与 v15.16、v15.15 各 APK 均内置使用知会条款（`NOTICE.txt`）；直链下载见上方官方网站。

**更新日志**：[changelog.html](https://fool593.github.io/LiquidGlassWatermark/changelog.html)（v15.17 澎湃闪退修复 / v15.16 光线恢复 / UI 优化 / YUV 定点优化 / 液态玻璃参数 / 动照片 5s 截断 / 字体持久化等）

---

## 语言 / Language

**中文** — 本软件内置**中英文切换**（另含印地语资源）。默认语言为中文；在「设置」页切换语言后立即生效并记住选择，
重启 / 切换主题 / 切换方案后保持不变。语言通过 `prefs` 键 `lang` 持久化，`attachBaseContext` 按所选语言加载资源。

**English** — This app supports **built-in Chinese / English language switching** (Hindi resources included as well).
Default language is Chinese; switch it on the **Settings** page and the change applies immediately and is remembered —
it survives app restart, theme switches and plan switches. Language preference is persisted under the `lang` key in
`SharedPreferences`, and resources are loaded per selected language via `attachBaseContext`.

| Resource | Locale | 说明 / Notes |
|---|---|---|
| `values/strings.xml` | 中文（默认） | 简体中文 |
| `values-en/strings.xml` | English | 英文界面 |
| `values-hi/strings.xml` | हिन्दी | 印地语资源 |

---

## 这是什么

本应用把玻璃光学效果作为**照片水印 / 视频水印**使用：

- **液态玻璃透镜效果** —— 水印区域不是一个平面贴图，而是一块会**折射背景**的玻璃：
  边缘有真正的 UV 折射位移、内部放大扭曲、色散（chromatic aberration）、菲涅尔边缘光与高光，
  背后内容透过玻璃变形，而不是简单的 alpha 混合。
- **照片水印** —— 文字 / 机型标识 / 品牌 Logo / 自定义 Logo / 布局排版，一行行像印在玻璃片上。
- **视频水印** —— 把静态水印渲染并烧录进视频（支持 MP4 硬编），或让水印随时间缓慢流动。
- **动态照片（Motion Photo）** —— 生成 vivo 双文件 / Google XMP 两种动照片格式，
  视频部分超过 5 秒时自动截取前 5 秒。
- **玻璃参数全开放** —— 折射强度、边缘厚度、色散、景深、高光、阴影、明暗、缩放补偿、画质档位全部可调。

## 快速预览

| 能力 | 说明 |
|---|---|
| 水印布局 | 经典（机型+时间双行）、单行胶囊、双行无标识、左右分栏 4 种 |
| 品牌 Logo | Leica / Zeiss / Hasselblad / Huawei / Xiaomi / vivo / XMAGE / ARRI / Sony / Samsung / Apple / Google / OPPO / OnePlus / 自定义 14 种 |
| 照片支持 | 单张编辑 + 批量保存（JPEG / PNG） |
| 视频支持 | 导入 / 编辑 / 导出 MP4（H.264 / H.265 硬编），GPU 快路径 + 逐帧兜底 |
| 动照片 | vivo 双文件（`.MP.jpg`）/ Google XMP 双格式输出 |
| 画质档 | High：3 抽样色散 + pow 菲涅尔 + 逐像素 bevel；Low：保留法线 UV 折射，色散/菲涅尔走廉价近似 |
| 缩放补偿 | 水印缩小时光学尺度自动反比补偿（参考 220px），小视口 / 低分辨率下效果不消失 |

## 构建

```bash
# Android Studio：打开仓库根目录，等待 Gradle 同步后 Run（或 assembleDebug）
# 命令行：
./gradlew :app:assembleDebug
```

首次构建需联网拉取依赖；离线环境请对齐 `app/build.gradle.kts` 中的依赖版本到本地缓存。

## 验证 / 自动化钩子（debug 构建，`am start --es ljgAutoTest ...`）

| 命令 | 作用 |
|---|---|
| `bench:5,14,15,16,30,60,300` | 视频导出性能基准（7 档时长 × gpu/gpuraw/cpu 三路径，CSV 落盘） |
| `exportgpu` / `exportgpu_cut` | GPU 路径导出 `auto_test.mp4`（第二个带 5 秒截断） |
| `motioncut` | 手动面板动照片全链路（5 秒截断 + 落盘 `.MP.jpg`） |
| `font:0\|1` / `planswitch:0\|1` | 字体模式切换 / 方案切换（验证字体持久化） |
| `glassperf` | 玻璃渲染层录制/跳过计数器（40s 采样窗口） |

## 许可证 / License

本仓库源码以 **MIT 许可证**发布（[LICENSE](LICENSE)）。项目不引用任何第三方 GitHub 开源库，
仅依赖 Google AndroidX / JetBrains 生态。MIT 许可之外，作者保留一条**非强制**的礼貌请求：
拉取或使用前知会作者一声（详见 [NOTICE.md](NOTICE.md)）。

This repository is released under the **MIT License** ([LICENSE](LICENSE)). It references no
third-party GitHub open-source libraries (only Google AndroidX / JetBrains components). Beyond the
MIT license, the author keeps one **non-binding** courtesy request: kindly notify the author when
you pull or use it. See [NOTICE.md](NOTICE.md).

## 目录结构

```
app/src/main/java/com/liquidglass/watermark/
├── MainActivity.kt            # 入口：编辑/导出/自动测试钩子
├── EditorState.kt             # 全局状态 + Params（所有可调参数与方案）
├── LiquidGlassEngine.kt       # 玻璃光学核心（逐像素物理：折射/色散/菲涅尔/高光）
├── WatermarkRenderer.kt       # 水印渲染（文字/Logo/玻璃合成/缓存）
├── GpuMp4Exporter.kt          # GPU 视频导出快路径（帧截图→水印→硬编）
├── Mp4Exporter.kt             # 视频导出（含逐帧兜底/动态照片 5s 截断）
├── MotionDecoder.kt           # 视频解码（双 worker 帧队列）
├── MotionPhotoWriter.kt       # vivo 双文件 / Google XMP 动照片封装
├── glass/                     # Compose 玻璃 UI 层（离屏层/阴影/高光/backdrop）
└── ui/                        # 编辑器界面（参数面板/滑块/开关/底栏）
```