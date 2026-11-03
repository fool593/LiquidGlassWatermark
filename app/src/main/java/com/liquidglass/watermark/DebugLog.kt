package com.liquidglass.watermark

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded asynchronous logging: never perform disk writes on rendering/codec threads. */
object DebugLog {
    private const val MAX_LOG_BYTES = 1024L * 1024L
    @Volatile private var appCtx: Context? = null
    private val lock = Any()
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "lgw-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val lastThrottle = ConcurrentHashMap<String, Long>()

    private fun logFile(): File? = appCtx?.let {
        File(it.getExternalFilesDir(null) ?: it.filesDir, "lgw_debug.log")
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        log("BOOT", "v$ver 启动")
    }

    fun log(tag: String, msg: String) {
        if (appCtx == null) return
        val timestamp = System.currentTimeMillis()
        val safeMessage = msg.take(8192)
        try {
            writer.execute {
                runCatching {
                    val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp)) +
                        " [$tag] $safeMessage\n"
                    synchronized(lock) {
                        val file = logFile() ?: return@synchronized
                        file.parentFile?.mkdirs()
                        if (file.length() >= MAX_LOG_BYTES) {
                            val previous = File(file.parentFile, "lgw_debug.log.1")
                            previous.delete()
                            if (!file.renameTo(previous)) file.writeText("")
                        }
                        file.appendText(line, Charsets.UTF_8)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // A diagnostic burst may drop messages; it must never stall a frame or
            // allocate an unbounded backlog. Existing queued messages stay ordered.
        }
    }

    /** Wait for accepted messages before reading/exporting; return false on timeout. */
    fun flush(timeoutMs: Long = 2000L): Boolean {
        if (Thread.currentThread().name == "lgw-log-writer") return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        val done = CountDownLatch(1)
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                writer.execute { done.countDown() }
                return done.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Reserve an ordered barrier as soon as the bounded queue drains.
                try { Thread.sleep(5L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun throttleLog(tag: String, msg: String, minGapMs: Long = 200L) {
        val now = SystemClock.elapsedRealtime()
        var accepted = false
        lastThrottle.compute(tag) { _, last ->
            if (last == null || now - last >= minGapMs.coerceAtLeast(0L)) {
                accepted = true
                now
            } else last
        }
        if (accepted) log(tag, msg)
    }

    fun logThrowable(tag: String, t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        log(tag, sw.toString().take(4000))
    }

    fun readRecent(maxChars: Int = 4000): String {
        flush(250L)
        return runCatching {
            val file = logFile()
            if (file == null || !file.exists()) return@runCatching "（暂无日志文件）"
            val chars = maxChars.coerceIn(1, 65536)
            synchronized(lock) {
                RandomAccessFile(file, "r").use { input ->
                    val count = minOf(input.length(), chars.toLong() * 4L).toInt()
                    val bytes = ByteArray(count)
                    input.seek(input.length() - count)
                    input.readFully(bytes)
                    String(bytes, Charsets.UTF_8).takeLast(chars)
                }
            }
        }.getOrDefault("（日志读取失败）")
    }

    /** Publish only after every byte is written. Failed pending rows are removed. */
    fun exportToDownload(): String? {
        if (!flush()) return null
        val ctx = appCtx ?: return null
        return runCatching {
            synchronized(lock) {
                val source = logFile() ?: return@synchronized null
                if (!source.exists() || source.length() == 0L) return@synchronized null
                val name = "lgw_debug_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".log"
                val previous = File(source.parentFile, "lgw_debug.log.1")
                if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = ctx.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/导出日志")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@synchronized null
                    try {
                        val output = resolver.openOutputStream(uri)
                            ?: throw java.io.IOException("No log output stream")
                        output.use { out ->
                            if (previous.exists()) previous.inputStream().use { it.copyTo(out) }
                            source.inputStream().use { it.copyTo(out) }
                            out.flush()
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        if (resolver.update(uri, values, null, null) <= 0) {
                            throw java.io.IOException("Unable to publish log")
                        }
                        "/storage/emulated/0/Download/导出日志/$name"
                    } catch (failure: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw failure
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "导出日志")
                    if (!dir.exists() && !dir.mkdirs()) return@synchronized null
                    val file = File(dir, name)
                    try {
                        file.outputStream().use { out ->
                            if (previous.exists()) previous.inputStream().use { it.copyTo(out) }
                            source.inputStream().use { it.copyTo(out) }
                        }
                        file.absolutePath
                    } catch (failure: Exception) {
                        file.delete()
                        throw failure
                    }
                }
            }
        }.onFailure { logThrowable("LOG_EXPORT", it) }.getOrNull()
    }

    fun deleteAllLogs(): Int {
        if (!flush()) return 0
        var deleted = 0
        synchronized(lock) {
            val main = logFile()
            if (main != null) {
                if (main.delete()) deleted++
                if (File(main.parentFile, "lgw_debug.log.1").delete()) deleted++
            }
            runCatching {
                val oldPublic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "lgw_debug.log")
                if (oldPublic.delete()) deleted++
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = appCtx?.contentResolver
                if (resolver != null) runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf("lgw_debug_%.log", Environment.DIRECTORY_DOWNLOADS + "/导出日志/"), null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                            if (resolver.delete(uri, null, null) > 0) deleted++
                        }
                    }
                }
            } else runCatching {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "导出日志")
                dir.listFiles { file -> file.name.startsWith("lgw_debug_") && file.name.endsWith(".log") }
                    ?.forEach { if (it.delete()) deleted++ }
            }
            Unit
        }
        return deleted
    }
}
