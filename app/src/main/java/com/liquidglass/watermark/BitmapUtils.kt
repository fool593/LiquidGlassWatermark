package com.liquidglass.watermark

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Movie
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 动态照片双文件配对评分纯函数（无 Android 依赖，JVM 可测）。
 *  ≥50 命中；同名 100；公共前缀≥8 按长度加分；同目录+时间±3s 加 30；
 *  同目录+时间±5s 直接抬到 60（vivo 命名变体兜底，与文件名无关）。 */
object CompanionScorer {
    fun score(baseName: String, videoName: String, dateTaken: Long, vDate: Long, sameDir: Boolean): Int {
        val base = baseName.substringBeforeLast('.').trim()
        val vn = videoName.substringBeforeLast('.').trim()
        if (base.isEmpty() || vn.isEmpty()) return 0
        var score = 0
        if (vn == base) score += 100
        else {
            var i = 0
            while (i < minOf(vn.length, base.length) && vn[i] == base[i]) i++
            if (i >= 8) score += i
        }
        if (dateTaken > 0 && vDate > 0 && kotlin.math.abs(vDate - dateTaken) <= 3000) score += 30
        if (sameDir) {
            score += 20
            // 兜底：即使文件名完全不同，同目录 + 拍摄时间差 ≤5s 也直接命中
            if (dateTaken > 0 && vDate > 0 && kotlin.math.abs(vDate - dateTaken) <= 5000) score = maxOf(score, 60)
        }
        return score
    }
}

object BitmapUtils {    class Info(val bitmap: Bitmap, val make: String?, val model: String?, val shotTime: String?)
    class GifInfo(val movie: Movie, val w: Int, val h: Int, val duration: Int)
    /** 判断字节是否为 GIF（GIF87a / GIF89a） */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
    /** 多路判定是否 GIF：字节魔数优先，其次内容提供器 MIME，再其次文件名后缀，任一命中即算动图 */
    fun looksLikeGif(ctx: Context, uri: Uri, bytes: ByteArray): Boolean {
        if (isGif(bytes)) return true
        runCatching {
            ctx.contentResolver.getType(uri)?.let { if (it.contains("gif", ignoreCase = true)) return true }
        }
        val seg = uri.lastPathSegment ?: ""
        return seg.endsWith(".gif", ignoreCase = true)
    }
    /** MIME 是否为其它类型的动图（如动态 WebP），用于给出明确提示而不是静默导出静态首帧 */
    fun isOtherAnimated(ctx: Context, uri: Uri): Boolean =
        runCatching {
            ctx.contentResolver.getType(uri)?.contains("webp", ignoreCase = true) == true
        }.getOrDefault(false)
    /**
     * 查找同名的配套视频（some motion-photo workflows store a still image and MP4 as separate media rows).
     * 匹配策略（综合评分）：同名（去扩展名）> 目录相同 + 拍摄时间相近（±3s）> 文件名公共前缀 ≥8 字符。
     * Returns null when no companion exists. Embedded-video JPEGs are handled by MotionPhoto instead.
     */
    fun findCompanionVideo(ctx: Context, imageUri: Uri): Uri? {
        return runCatching {
            // 文件名优先走 OpenableColumns（对系统选择器的 DocumentProvider 通用），
            // MediaStore 列查不到（非相册 URI）时回退为 0，仅靠文件名匹配
            var base = ""
            runCatching {
                ctx.contentResolver.query(
                    imageUri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ci = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (ci >= 0) base = c.getString(ci)?.substringBeforeLast('.')?.trim() ?: ""
                    }
                }
            }
            var dateTaken = 0L; var relPath: String? = null; var dataPath = ""
            runCatching {
                ctx.contentResolver.query(
                    imageUri,
                    arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.RELATIVE_PATH,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_MODIFIED
                    ), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        if (base.isEmpty()) {
                            val nIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                            if (nIdx >= 0) base = c.getString(nIdx)?.substringBeforeLast('.')?.trim() ?: ""
                        }
                        dateTaken = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                        // vivo 等 ROM 的 DATE_TAKEN 常为 0：用 DATE_MODIFIED（秒）兜底换算毫秒
                        if (dateTaken <= 0) {
                            val dmIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                            if (dmIdx >= 0) {
                                val dm = c.getLong(dmIdx)
                                if (dm > 0) dateTaken = dm * 1000L
                            }
                        }
                        val rpIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (rpIdx >= 0) relPath = c.getString(rpIdx)
                        val dIdx = c.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dIdx >= 0) dataPath = c.getString(dIdx) ?: ""
                    }
                }
            }
            if (base.isEmpty()) return@runCatching null
            // 反查 MediaStore.Images：DocumentsProvider URI 查不到日期/目录时，
            // 用文件名去 Images 表补齐 DATE_TAKEN/RELATIVE_PATH/DATA，让目录+时间兜底生效
            if (dateTaken == 0L) {
                runCatching {
                    val cols = arrayOf(
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.RELATIVE_PATH,
                        MediaStore.Images.Media.DATA
                    )
                    for (ext in listOf(".jpg", ".jpeg", ".heic", ".png", ".heif")) {
                        // LIKE 前缀而非精确等值：vivo 等 ROM 可能对文件名加后缀变体（如 _edit），
                        // 精确匹配会漏掉，导致目录+时间兜底拿不到元数据
                        ctx.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                            arrayOf("$base%"), null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                dateTaken = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                                val rp = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                                if (rp >= 0) relPath = c.getString(rp)
                                val d = c.getColumnIndex(MediaStore.Images.Media.DATA)
                                if (d >= 0) dataPath = c.getString(d) ?: ""
                            }
                        }
                        if (dateTaken > 0) break
                    }
                }
            }
            var best: Uri? = null; var bestScore = 0
            // 时间兜底：全部候选低分时，取拍摄时间与封面最接近的视频（vivo 动态照片视频与封面拍摄时间紧邻）
            var bestTimeGap = Long.MAX_VALUE
            var bestTimeUri: Uri? = null
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DATE_MODIFIED
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val vName = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                    var vDate = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN))
                    // vivo 等 ROM 视频 DATE_TAKEN 常为 0：用 DATE_MODIFIED（秒）兜底换算毫秒
                    if (vDate <= 0) {
                        val vdmIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                        if (vdmIdx >= 0) {
                            val vdm = c.getLong(vdmIdx)
                            if (vdm > 0) vDate = vdm * 1000L
                        }
                    }
                    // 时间最近兜底：封面时间未知时跳过时间比较，交给分数路径
                    if (dateTaken > 0 && vDate > 0) {
                        val gap = kotlin.math.abs(vDate - dateTaken)
                        if (gap < bestTimeGap) {
                            bestTimeGap = gap
                            bestTimeUri = android.content.ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                            )
                        }
                    }
                    val vRpIdx = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    var dirSame = false
                    if (vRpIdx >= 0 && relPath != null && relPath == c.getString(vRpIdx)) {
                        dirSame = true
                    } else {
                        val vDIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                        if (vDIdx >= 0 && dataPath.isNotEmpty()) {
                            val vData = c.getString(vDIdx) ?: ""
                            if (vData.isNotEmpty() &&
                                vData.substringBeforeLast('/') == dataPath.substringBeforeLast('/')) dirSame = true
                        }
                    }
                    val score = CompanionScorer.score(base, vName, dateTaken, vDate, dirSame)
                    if (score >= 50 && score > bestScore) {
                        bestScore = score
                        best = android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                        )
                    }
                }
            }
            // 分数路径无高置信候选：时间最近兜底（≤10 秒视为同一拍摄瞬间，绑定最接近的视频）
            if (best == null && bestTimeUri != null && bestTimeGap <= 10_000L) {
                best = bestTimeUri
            }
            best
        }.getOrNull()
    }

    /** 反向配对：用户直接选中动态照片的"视频"条目时，找同名/拍摄时间接近的静态图片作为封面 */
    fun findCompanionImage(ctx: Context, videoUri: Uri): Uri? {
        return runCatching {
            var base = ""
            runCatching {
                ctx.contentResolver.query(
                    videoUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ci = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (ci >= 0) base = c.getString(ci)?.substringBeforeLast('.')?.trim() ?: ""
                    }
                }
            }
            if (base.isEmpty()) return@runCatching null
            var vDate = 0L
            runCatching {
                ctx.contentResolver.query(
                    videoUri, arrayOf(MediaStore.Video.Media.DATE_TAKEN), null, null, null
                )?.use { c -> if (c.moveToFirst()) vDate = c.getLong(0) }
            }
            var best: Uri? = null; var bestScore = 0
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val iName = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: continue
                    val iDate = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                    var score = CompanionScorer.score(base, iName, iDate, vDate, false)
                    // 反向场景不依赖目录：拍摄时间差 ≤5s 也直接抬到 55 命中
                    if (iDate > 0 && vDate > 0 && kotlin.math.abs(iDate - vDate) <= 5000) score = maxOf(score, 55)
                    if (score >= 50 && score > bestScore) {
                        bestScore = score
                        best = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        )
                    }
                }
            }
            best
        }.getOrNull()
    }
    /** 供外部一次性读取 URI 字节（识别格式 / GIF 解码） */
    fun readBytes(ctx: Context, uri: Uri): ByteArray = loadBytes(ctx, uri)
    /** 解码 GIF 动图为 Movie（全 API 可用的软件解码） */
    fun decodeGif(bytes: ByteArray): GifInfo {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: error("无法解码该动图（仅支持 GIF 格式）")
        val w = movie.width(); val h = movie.height()
        if (w <= 0 || h <= 0) error("动图尺寸异常")
        var dur = movie.duration()
        if (dur <= 0) dur = 1000
        return GifInfo(movie, w, h, dur)
    }
    /**
     * 轻量 GIF 时间线解析：只扫 Graphic Control Extension (0x21 0xF9) 的 delay 字段，
     * 不解码任何像素。返回每帧的停留时间（毫秒，可能为 0 表示采用默认 100ms）。
     * 解析失败或非标准结构时返回 null，调用方回退到固定帧率。
     */
    fun gifTimeline(bytes: ByteArray): IntArray? {
        if (bytes.size < 14 || String(bytes, 0, 3, Charsets.US_ASCII) != "GIF") return null
        var position = 13
        fun u8(index: Int) = bytes[index].toInt() and 255
        fun skipSubBlocks(): Boolean {
            while (position < bytes.size) {
                val count = u8(position++)
                if (count == 0) return true
                if (count > bytes.size - position) return false
                position += count
            }
            return false
        }
        if (u8(10) and 0x80 != 0) position += 3 * (1 shl ((u8(10) and 7) + 1))
        if (position >= bytes.size) return null
        val delays = ArrayList<Int>()
        var nextFrameDelay = 0
        while (position < bytes.size) {
            when (u8(position++)) {
                0x21 -> {
                    if (position >= bytes.size) return null
                    val label = u8(position++)
                    if (label == 0xF9) {
                        // position points to the block SIZE, followed by packed flags, Lo, Hi.
                        // Reading packed flags as Lo previously multiplied most frame delays by 256.
                        if (position + 6 > bytes.size || u8(position) != 4 || u8(position + 5) != 0) return null
                        nextFrameDelay = (u8(position + 2) or (u8(position + 3) shl 8)) * 10
                    }
                    if (!skipSubBlocks()) return null
                }
                0x2C -> {
                    // Left/top/width/height + packed flags (nine bytes after image separator).
                    if (position + 9 > bytes.size) return null
                    val flags = u8(position + 8)
                    position += 9
                    if (flags and 0x80 != 0) position += 3 * (1 shl ((flags and 7) + 1))
                    if (position >= bytes.size) return null
                    position++ // LZW minimum code size
                    if (!skipSubBlocks()) return null
                    // A GCE applies to the next image only; images without one use the default.
                    delays.add(nextFrameDelay)
                    nextFrameDelay = 0
                }
                0x3B -> return delays.takeIf { it.isNotEmpty() }?.toIntArray()
                else -> return null
            }
        }
        return null // A truncated stream must not be treated as a valid timeline.
    }
    private data class Exif(val make: String?, val model: String?,
                            val shot: String?, val orientation: Int)

    /** 自定义预览图：从 Uri 读取 → 采样解码（1440 上限防 OOM）→ EXIF 转正；失败返回 null */
    fun loadPreviewCustom(ctx: Context, uri: Uri): Bitmap? =
        runCatching { decodePreviewBytes(loadBytes(ctx, uri)).bitmap }.getOrNull()

    /** 一次性把图片字节读入内存，后续全部从字节解码，规避重复开流 / 相册 URI 兼容问题 */
    private fun loadBytes(ctx: Context, uri: Uri): ByteArray {
        val viaStream = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (viaStream != null && viaStream.isNotEmpty()) return viaStream
        // 流读取失败/为空（vivo 等 ROM 的 MediaProvider 对动态照片视频条目可能返回空流/半截流）：
        // 按 DATA 路径直接读文件兜底
        val data = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (data != null && data.isNotEmpty()) {
            val f = File(data)
            if (f.exists() && f.length() > 0) return f.readBytes()
        }
        if (viaStream != null) error("图片内容为空") else error("无法读取该图片")
    }

    private fun boundsOf(bytes: ByteArray): BitmapFactory.Options {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        return opt
    }

    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > maxEdge) s *= 2
        return s
    }

    private fun exifOf(bytes: ByteArray): Exif {
        var make: String? = null; var model: String? = null
        var shot: String? = null; var ori = ExifInterface.ORIENTATION_NORMAL
        runCatching {
            ByteArrayInputStream(bytes).use { ins ->
                val exif = ExifInterface(ins)
                make = exif.getAttribute(ExifInterface.TAG_MAKE)
                model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                shot = normalizeExifTime(raw)
                ori = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }
        return Exif(make, model, shot, ori)
    }

    private fun normalizeExifTime(raw: String?): String? {
        raw ?: return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val d: Date = sdf.parse(raw) ?: return null
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
        }.getOrNull()
    }

    private fun decodeBytes(bytes: ByteArray, maxEdge: Int): Bitmap {
        val b = boundsOf(bytes)
        if (b.outWidth <= 0 || b.outHeight <= 0) {
            error("无法解码该图片（格式可能不被支持）")
        }
        val exif = exifOf(bytes)
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(b.outWidth, b.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
            ?: error("图片解码失败，可能内存不足或文件损坏")
        val deg = when (exif.orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return raw
        return runCatching {
            val m = Matrix().apply { postRotate(deg) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (rotated !== raw) raw.recycle()
            rotated
        }.getOrElse { raw }
    }

    fun decodePreviewBytes(bytes: ByteArray): Info {
        val exif = exifOf(bytes)
        val bmp = decodeBytes(bytes, 1440)
        return Info(bmp, exif.make, exif.model, exif.shot)
    }

    /**
     * 动图封面专用解码兜底链（与静态预览同级的 EXIF 转正，补 ImageDecoder 兜底）：
     * 采样解码（防超大图 OOM）→ 应用 EXIF Orientation（竖屏照片转正，否则动图封面横屏）→
     * 失败换 ImageDecoder（API 28+，HEIF 等 BitmapFactory 解不了的格式，ImageDecoder 自动应用 EXIF）。
     * 失败返回 null，由调用方提示。
     */
    fun decodeCover(bytes: ByteArray, maxEdge: Int = 4096): Bitmap? {
        return try {
            val b = boundsOf(bytes)
            if (b.outWidth <= 0 || b.outHeight <= 0) return null
            val exif = exifOf(bytes)
            val opt = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(b.outWidth, b.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
            }.getOrNull()
            if (raw != null) return applyExifRotation(raw, exif.orientation)
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder 自动应用 EXIF Orientation，无需二次旋转
                return runCatching {
                    val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        // Preview/export use a software Canvas and must be able to sample pixels.
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val s = sampleSize(info.size.width, info.size.height, maxEdge)
                        if (s > 1) decoder.setTargetSampleSize(s)
                    }
                }.getOrNull()
            }
            null
        } catch (_: Throwable) { null }
    }

    /** BitmapFactory 不应用 EXIF，按 Orientation 转正（翻转类不常见，仅处理旋转） */
    private fun applyExifRotation(bmp: Bitmap, orientation: Int): Bitmap {
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        return runCatching {
            val m = Matrix().apply { postRotate(deg) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        }.getOrElse { bmp }
    }

    /**
     * 封面按目标比例 center-crop 居中裁剪后缩放（保持比例不变形）。
     * 动态照片封面与视频帧比例不一致时若直接 createScaledBitmap 会不等比拉伸 → 封面变形/比例怪异。
     */
    fun centerCrop(src: Bitmap, tw: Int, th: Int): Bitmap {
        if (src.isRecycled || tw <= 0 || th <= 0) return src
        val sw = src.width.coerceAtLeast(1); val sh = src.height.coerceAtLeast(1)
        val srcRatio = sw.toFloat() / sh
        val dstRatio = tw.toFloat() / th
        var cx = sw; var cy = sh
        if (srcRatio > dstRatio) cx = (sh * dstRatio).toInt().coerceIn(1, sw)
        else cy = (sw / dstRatio).toInt().coerceIn(1, sh)
        val x = ((sw - cx) / 2).coerceAtLeast(0); val y = ((sh - cy) / 2).coerceAtLeast(0)
        val cropped = runCatching {
            Bitmap.createBitmap(src, x, y, cx.coerceAtMost(sw - x), cy.coerceAtMost(sh - y))
        }.getOrNull() ?: return src
        val scaled = Bitmap.createScaledBitmap(cropped, tw.coerceAtLeast(1), th.coerceAtLeast(1), true)
        if (scaled !== cropped && cropped !== src) cropped.recycle()
        return scaled
    }

    /** 导出用全分辨率解码（内存保护：最长边不超过 6000px） */
    fun decodeFull(ctx: Context, uri: Uri): Bitmap {
        val bytes = loadBytes(ctx, uri)
        return decodeBytes(bytes, 6000)
    }

    /**
     * 保存到系统相册，兼容 Android 8-14。
     * Android 10+ 走 MediaStore（无需权限）；8/9 写公共 DCIM 并扫描入库。
     * 返回用户可见的保存位置描述。
     */
    fun saveToGallery(ctx: Context, bmp: Bitmap): String {
        val name = "LGM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/LiquidGlass")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("相册写入失败（MediaStore 不可用）")
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
                    os.flush()
                } ?: error("相册输出流创建失败")
            } finally {
                // 写入完成或失败都立即结束 pending——进程被杀也不残留"相册看不见"的记录
                runCatching {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
            "DCIM/LiquidGlass/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "LiquidGlass")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { os ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
                os.flush()
            }
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            file.absolutePath
        }
    }
    /** 保存 GIF 动图到系统相册，兼容 Android 8-14 */
    fun saveGifToGallery(ctx: Context, bytes: ByteArray): String {
        val name = "LGM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gif"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/LiquidGlass")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("相册写入失败（MediaStore 不可用）")
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes); os.flush()
                } ?: error("相册输出流创建失败")
            } finally {
                // 写入完成或失败都立即结束 pending——进程被杀也不残留"相册看不见"的记录
                runCatching {
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
            "DCIM/LiquidGlass/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "LiquidGlass")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(file.absolutePath), arrayOf("image/gif"), null)
            file.absolutePath
        }
    }
}
