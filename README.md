# LiquidGlass Watermark · 液态玻璃水印 v15.41

Android 图片 / 视频水印编辑器。Kotlin + Jetpack Compose，底部导航为 **KernelSU 管理器真源码移植的液态玻璃悬浮 Dock**。

- 包名：`com.liquidglass.watermark`（debug 包为 `com.liquidglass.watermark.debug`）
- 版本：**versionCode 1541 / versionName 15.41**
- 平台：minSdk 26 / targetSdk 37 / compileSdk 37，JVM 17

---

## 1. 本版更新（v15.41）

### 1.1 Dock 变成真正的悬浮液态玻璃（透得过、看得见后面）

| 改动 | 说明 |
|---|---|
| 参数面板延长到屏幕底 | 面板高度由 232dp 改为 **232dp + Dock 占位**，一直铺到屏幕底边穿过 Dock。Dock 玻璃后面不再是空背景板，而是真实的参数内容——隔着一层玻璃能看到后面的选项 |
| 导出栏与 Dock 合为底部悬浮层 | 导出栏（MP4 / HEVC / GIF / 模式）位置不变，仍紧贴 Dock 正上方；两者一起作为悬浮层压在面板上，不再占用 Column 的流式空间 |
| 整条 Dock 栏玻璃化 | 底部悬浮层整体做一块通体玻璃（模糊 14dp + 通透增强 + 极淡染色，顶部 26dp 圆角）。不是只有胶囊是玻璃，整条栏都透光 |
| 胶囊更透明 | 胶囊表面填充 0.4 → **0.14**，更接近 iOS 的透明玻璃胶囊，后方的参数内容透过透镜可见 |

### 1.2 按住胶囊的重影修掉

重影有两个来源，都处理了：

- **隐形镜像层**：KSU 原实现用一条 `.alpha(0f) + drawBackdrop` 的整宽副本把图标记录到 `tabsBackdrop` 供放大镜取样。但 `drawBackdrop` 的合成层不受外层 alpha 控制，实际会再画一遍 44dp 高的胶囊底色 → 按住时出现上下两层轮廓。**该副本已删除**，镜片只取真实背景。
- **整条按压高光**：`interactiveHighlight` 在按下时会在整行铺一层方角高光板，看起来像多出来的一层底。**该层已移除**，按压反馈改由镜片单独承担。
- 顺带把胶囊的按压放大（16dp 缩放）去掉——缩放会让玻璃层绘制边界露出直边色块。

### 1.3 分层重构：玻璃 → 镜片 → 内容

Dock 由单层改为三层，内容永远画在镜片之上：

1. 胶囊玻璃层（阴影 / 折射 / 高光，按胶囊形状裁剪）
2. 镜片层（选中指示 / 按下放大，固定半径胶囊片，底色只留 6% 淡色）
3. 内容层（图标 + 文字，选中项用强调蓝；点击、拖动、镜片跟随全部保持原逻辑）

原来镜片是不透明黑底，会把选中格的图标整块盖掉；现在图标始终清晰，也不会再出现"按住后图标消失 / 重影"。

## 2. v15.40 要点

- Dock 胶囊改为固定半径（半径随高度 26dp，不随宽度），缩短后仍是胶囊而非椭圆；指示片同步改形，胶囊内衬 4dp → 10dp。

## 3. v15.39 要点

- 字体切换彻底修复：扫描 `/system/fonts` 真实字库并按"能否渲染出字形"校验，`applyTypeface()` 逐帧同步 typeface。
- Dock 紧凑居中（项数 × 62dp + 22dp，5 项 ≈ 330dp）、底距 22dp。

## 4. 已知问题与限制

- 面板被 Dock 压住的最后几行需要下滑才能看全（这是悬浮 Dock 的预期行为，滚动即可）。
- 底部悬浮层的模糊在低端机上会有一点点 GPU 开销（玻璃整体只有一层，影响可控）。
- 主题字体判定依赖字形比对，vivo / OPPO / 小米各代主题字库未逐一真机验证。
- 「真实地名」需定位权限；无权限或模拟器无地理数据时该行不绘制。
- 超大字号（>100sp 且文案长）标题可能横向超出玻璃条，仅保证纵向不溢出。
- `app/src/test/**` 引用不存在的 `core.export` 包，`testDebugUnitTest` 无法编译，不影响 assemble。
- 平板横向布局未专门适配（Dock 封顶 560dp 居中）。
- 导出栏在窄屏上需横向滑动，最右侧按钮默认在可视区外。

## 5. 安装

下载 `LiquidGlassWatermark_v15.41_release.apk`（Release 附件）直接覆盖安装（同签名）。

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
