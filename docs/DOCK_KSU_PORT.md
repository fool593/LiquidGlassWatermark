# 液态玻璃 Dock：KernelSU 源码移植说明（v15.34 起，v15.35 压缩尺寸）

> v15.35 尺寸调整：胶囊 64→52dp、指示器 56→44dp、左右留白 28→16dp、无导航栏底距 28→10dp、
> 图标 22→16dp、字标 11→9.5sp。玻璃参数（vibrancy / blur 4dp / lens 24,24 / 0.4 容器色 /
> 双光源高光 / 拖动惯性 / 速度拉伸 / 橡皮筋）与 KSU 逐项一致，只压缩尺寸给画布让位。
> 图标为按 SF Symbols 线条语言重绘的工程内 Canvas glyph（KSU 原用 Material Icons）。

## 一、移植来源（真实 KSU 源码，非自行实现）

| 文件 | 上游路径（tiann/KernelSU，`main` 分支） |
| --- | --- |
| `ui/ksu/FloatingBottomBar.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt` |
| `ui/ksu/DampedDragAnimation.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/animation/DampedDragAnimation.kt` |
| `ui/ksu/InteractiveHighlight.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/animation/InteractiveHighlight.kt` |
| `ui/ksu/DragGestureInspector.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/modifier/DragGestureInspector.kt` |
| `ui/ksu/InnerShadow.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/InnerShadow.kt` |
| `glass/effects/KsuLens.kt` | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/Lens.kt` |
| 调用方 `BottomBarMiuix` 的布局规则 | `manager/app/src/main/java/me/weishu/kernelsu/ui/component/bottombar/BottomBarMiuix.kt` |

上游说明：`FloatingBottomBar.kt` 头部注明 *"Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0"*，
`liquid/*` 注明 *"Adapted from Kyant0/AndroidLiquidGlass — Apache 2.0, mirrored from compose-miuix-ui example"*。
本工程 `glass/` 包本来就是同一上游的移植，因此玻璃光路、层捕获、内阴影、动画参数全部可以直接对齐。

## 二、保留的 KSU 原始参数（逐项未改）

- 玻璃本体：`height 64.dp` + `padding 4.dp`、`dropShadow radius 10.dp / alpha 0.2(暗) 0.1(亮)`、
  `padding = max(padding, 40.dp)`、`vibrancy()`、`blur(4.dp)`、`lens(24.dp, 24.dp)`、
  `containerColor = surfaceContainer.copy(alpha = 0.4)`。
- 选中指示器：`height 56.dp`、宽度 = 单格 `tabWidth`、`padding(horizontal = 4.dp)`、
  `translationX = value * tabWidth + 弹性回弹偏移`、`lens(10.dp * press, 14.dp * press, depthEffect = true, chromaticAberration = 0.5f)`、
  `innerShadow(radius = 8.dp * press, alpha = press)`、按压 `scaleX/scaleY + 速度拉伸`、
  `onDrawSurface` 的 0.1 黑白罩 + 0.03 按压罩。
- 放大副本层：`alpha(0f)` + `layerBackdrop(tabsBackdrop)` 录一份"强调色"内容，
  指示器用 `rememberCombinedBackdrop(backdrop, tabsBackdrop)` 折射它 —— 这就是 KSU 选中项内
  图标/文字变蓝并被玻璃放大的机制。
- 交互：`DampedDragAnimation(pressedScale = 78f / 56f)`、拖动惯性、越界橡皮筋（4dp + EaseOut）、
  `InteractiveHighlight` 的按压光斑（AGSL + Plus 混合）、空白区域 `detectTapGestures {}` 吞点击。
- 布局：左右 `28.dp`；底部 `inset != 0 → 8.dp + inset`，否则 `28.dp`（`BottomBarMiuix` 原规则）。

## 三、仅为"可移植"做的对接（不改视觉参数）

1. `top.yukonga.miuix.kmp.blur.*` → 本工程同源的 `com.liquidglass.watermark.glass.*`
   （`Backdrop` / `drawBackdrop` / `blur` / `vibrancy` / `rememberLayerBackdrop` / `rememberCombinedBackdrop`）。
2. `liquid/lens` → `glass/effects/KsuLens.kt`：同一份 AGSL 折射/色散着色器，色散强度按 KSU 的 `float` 传入。
3. `MiuixTheme.colorScheme` → 本工程深浅色常量（`surfaceContainer`：暗 `#2C2C2E` / 亮 `#F2F2F7`，
   `primary` → `#0091FF`，`onSurface` → 文本主色）。
4. miuix `BloomStroke` 双光源高光（依赖 miuix-blur 库内私有着色器）→ 同源 `HighlightStyle.Default`，
   光照角度取 KSU 的 `-45°`（本体）/ `+45°`（指示器）；设备重力感应只改高光角度、不影响导航逻辑，故固定。
5. 指示器 `width(IntrinsicSize.Min)` 依赖 KSU 的 4 tab × `minWidth 76.dp`；本应用是 6 tab，改为等宽铺满。

## 四、顺带修掉的遮挡与溢出

- 页面底部原来只留 `14.dp`，Dock 实测高度（`64 + 28或(8+inset)`）远大于它，导致常驻导出栏
  （**MP4 / HEVC / GIF**、快速/混合/CPU、动照、清缓存）被整条盖住。现在由 `onSizeChanged` 实测 Dock 高度，
  在 `Column` 末尾 `Spacer(实测高度 + 6dp)` 精确占位，任何机型/字体缩放/导航栏高度都自动对齐。
- `glass/DrawBackdropModifier.kt`：`padding > 0` 的玻璃（blur/lens 外溢区）现在按 shape 裁剪后再合成。
  修复前外溢的折射像素会画到控件边界之外 —— 液态玻璃 Dock 上表现为一个横跨数格、超出胶囊上下沿的
  "放大圆斑"（截图里的那团畸变椭圆）。
