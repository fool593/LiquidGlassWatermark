// v15.36：跨机型尺寸适配。
//
// 问题：真机上（系统显示大小/字体大小调过）整套 Compose 界面被系统 density/fontScale 放大，
// 面板被挤扁、留白消失，Dock 胶囊反而因为「按内容包住」变得又短又圆，和模拟器完全两个样子。
//
// 做法：只在 Compose 根包一层 LocalDensity 覆盖，不改任何业务代码里的 dp/sp 数值：
//   1. 按【屏幕宽度 dp】给一档基准缩放 —— 小屏收一点、平板放大一点，视觉尺寸跨设备一致；
//   2. fontScale 夹在 0.92~1.06，避免系统超大字体把布局撑爆、把纵向空间吃干。
// 系统 density 仍参与计算（不做绝对像素化），所以真机物理清晰度不受影响。
package com.liquidglass.watermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 屏幕宽度 → UI 基准缩放（相对 393dp 的主流手机）。
 * 手机区间变化很小（0.92~1.02），避免同一档机型之间跳变；
 * 平板（≥600dp）才明显放大，因为平板上 12sp 的字按手机比例看会偏小。
 */
fun uiScaleForWidth(widthDp: Int): Float = when {
    widthDp >= 1000 -> 1.45f   // 大平板 / 折叠展开
    widthDp >= 840 -> 1.30f
    widthDp >= 720 -> 1.20f
    widthDp >= 600 -> 1.12f
    widthDp >= 480 -> 1.04f
    widthDp >= 420 -> 1.00f
    widthDp >= 380 -> 0.96f
    widthDp >= 340 -> 0.92f
    else -> 0.88f
}

@Composable
fun ProvideAdaptiveUiScale(content: @Composable () -> Unit) {
    val sys = LocalDensity.current
    val conf = LocalConfiguration.current
    val scale = uiScaleForWidth(conf.screenWidthDp)
    // 字体缩放保留一点用户意愿，但不允许把布局撑到变形
    val fontScale = sys.fontScale.coerceIn(0.92f, 1.06f)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = sys.density * scale,
            fontScale = fontScale
        )
    ) {
        content()
    }
}
