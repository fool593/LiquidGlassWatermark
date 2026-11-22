package com.liquidglass.watermark

import android.app.Application
import android.content.Context
import android.os.Process
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

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

        @JvmStatic
        lateinit var appContext: Context
            private set

        fun showCrashLog(ctx: Context) {
            runCatching {
                val f = File(ctx.getDir("crash", Context.MODE_PRIVATE), "last.txt")
                if (f.exists() && f.length() > 0) {
                    val tail = f.readText().trim().lines().takeLast(14).joinToString("\n")

                    runCatching { f.delete() }
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
