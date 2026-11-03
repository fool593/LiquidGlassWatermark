package com.liquidglass.watermark

import android.app.Application
import android.content.Context
import android.os.Process
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Record uncaught failures, then delegate to Android's normal crash handling.
 * Restarting a broken UI in the exception handler caused an endless launch/crash loop.
 * 崩溃堆栈写入内部存储 app_crash/last.txt，下次启动 Toast 显示尾部，便于远程定位。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.append("=== crash ").append(System.currentTimeMillis()).append(" ===\n")
                sb.append("sdk=").append(android.os.Build.VERSION.SDK_INT)
                    .append(" ").append(android.os.Build.MANUFACTURER)
                    .append(" ").append(android.os.Build.MODEL).append("\n")
                sb.append(sw.toString()).append("\n")
                File(getDir("crash", MODE_PRIVATE), "last.txt").writeText(sb.toString())
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex)
            } else {
                Process.killProcess(Process.myPid())
                Runtime.getRuntime().exit(10)
            }
        }
    }

    companion object {
        /** 全局 Application Context（品牌 logo 从 assets 加载等场景使用） */
        @JvmStatic
        lateinit var appContext: Context
            private set

        /** 启动时调用：若上次崩溃过，Toast 显示堆栈尾部 */
        fun showCrashLog(ctx: Context) {
            runCatching {
                val f = File(ctx.getDir("crash", Context.MODE_PRIVATE), "last.txt")
                if (f.exists() && f.length() > 0) {
                    val tail = f.readText().trim().lines().takeLast(14).joinToString("\n")
                    Toast.makeText(
                        ctx,
                        "上次发生崩溃，请把这段反馈给开发者：\n$tail",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
