package com.liquidglass.watermark.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ListView
import android.widget.CheckedTextView
import com.liquidglass.watermark.darkModeState

/** Apply after show(), when the platform dialog's views and window exist. */
fun AlertDialog.applyLiquidGlassStyle(): AlertDialog {
    val dark = darkModeState
    val density = context.resources.displayMetrics.density
    val foreground = if (dark) Color.rgb(242, 246, 255) else Color.rgb(26, 35, 52)
    val accent = if (dark) Color.rgb(98, 179, 255) else Color.rgb(0, 91, 192)
    window?.apply {
        setBackgroundDrawable(GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (dark) intArrayOf(0xE834405B.toInt(), 0xEB172136.toInt(), 0xE5282545.toInt())
            else intArrayOf(0xF2FFFFFF.toInt(), 0xEBE7F0FC.toInt(), 0xF0F3EAFB.toInt())
        ).apply {
            cornerRadius = 24f * density
            setStroke((density * 0.7f).toInt().coerceAtLeast(1),
                if (dark) 0x66FFFFFF else 0xCCFFFFFF.toInt())
        })
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(if (dark) 0.38f else 0.22f)
        decorView.elevation = 18f * density
        // Android silently ignores window blur when disabled by system/device policy.
        // The translucent gradient above remains readable on Android 9-11.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { setBackgroundBlurRadius((24f * density).toInt()) }
        }
    }
    fun style(view: View) {
        when (view) {
            is Button -> view.setTextColor(accent)
            is EditText -> {
                view.setTextColor(foreground)
                view.setHintTextColor(if (dark) 0xFFABB7CC.toInt() else 0xFF596980.toInt())
                view.backgroundTintList = ColorStateList.valueOf(accent)
            }
            is CheckedTextView -> {
                view.setTextColor(foreground)
                view.checkMarkTintList = ColorStateList.valueOf(accent)
            }
            is TextView -> view.setTextColor(foreground)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) style(view.getChildAt(i))
        }
        if (view is ListView) {
            view.setCacheColorHint(Color.TRANSPARENT)
            // List rows are recycled/created after show(), including on scrolling.
            view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { child?.let(::style) }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }
    }
    window?.decorView?.let(::style)
    return this
}
