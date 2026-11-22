package com.liquidglass.watermark

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

object MotionPhotoWriter {

    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"

    // Besides the Google namespace, this template carries observed Oplus/Xiaomi namespace fields as
    // compatibility hints. Their presence is not a statement that any current OEM gallery accepts
    // this output; those combinations require physical-device verification.

    private const val XMP_TEMPLATE =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description rdf:about=\"\"" +
        " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
        " xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"" +
        " xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\"" +
        " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
        " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">" +
        "<GCamera:MotionPhoto>1</GCamera:MotionPhoto>" +
        "<GCamera:MotionPhotoVersion>1</GCamera:MotionPhotoVersion>" +
        "<GCamera:MotionPhotoPresentationTimestampUs>-1</GCamera:MotionPhotoPresentationTimestampUs>" +
        "<GCamera:MicroVideo>1</GCamera:MicroVideo>" +
        "<GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>" +
        "<GCamera:MicroVideoOffset>%1\$d</GCamera:MicroVideoOffset>" +
        "<GCamera:MicroVideoPresentationTimestampUs>-1</GCamera:MicroVideoPresentationTimestampUs>" +
        "<GCamera:MotionPhotoVideoEnd>%2\$d</GCamera:MotionPhotoVideoEnd>" +
        // Oplus namespace candidate fields (unverified across current gallery releases).
        "<OpCamera:MotionPhoto>1</OpCamera:MotionPhoto>" +
        "<OpCamera:MotionPhotoVersion>1</OpCamera:MotionPhotoVersion>" +
        "<OpCamera:MotionPhotoPrimaryPresentationTimestampUs>-1</OpCamera:MotionPhotoPrimaryPresentationTimestampUs>" +
        "<OpCamera:MotionPhotoEnable>True</OpCamera:MotionPhotoEnable>" +
        "<OpCamera:MotionPhotoOwner>oplus</OpCamera:MotionPhotoOwner>" +
        "<OpCamera:OLivePhotoVersion>2</OpCamera:OLivePhotoVersion>" +
        "<OpCamera:VideoLength>%1\$d</OpCamera:VideoLength>" +
        // Xiaomi namespace candidate fields (unverified across current gallery releases).
        "<MiCamera:MotionPhoto>1</MiCamera:MotionPhoto>" +
        "<MiCamera:MotionPhotoVersion>1</MiCamera:MotionPhotoVersion>" +
        "<MiCamera:MotionPhotoPresentationTimestampUs>-1</MiCamera:MotionPhotoPresentationTimestampUs>" +
        "<Container:Directory>" +
        "<rdf:Seq>" +
        "<rdf:li rdf:parseType=\"Resource\">" +
        "<Item:Mime>image/jpeg</Item:Mime>" +
        "<Item:Semantic>Primary</Item:Semantic>" +
        "<Item:Length>0</Item:Length>" +
        "<Item:Padding>0</Item:Padding>" +
        "</rdf:li>" +
        "<rdf:li rdf:parseType=\"Resource\">" +
        "<Item:Mime>video/mp4</Item:Mime>" +
        "<Item:Semantic>MotionPhoto</Item:Semantic>" +
        "<Item:Length>%1\$d</Item:Length>" +
        "<Item:Padding>0</Item:Padding>" +
        "</rdf:li>" +
        "</rdf:Seq>" +
        "</Container:Directory>" +
        "</rdf:Description></rdf:RDF></x:xmpmeta>"

    fun composeToGallery(ctx: Context, jpeg: ByteArray, videoUri: Uri, baseName: String = "IMG_"): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }

        val tmp = File.createTempFile("mp_", ".mp4", ctx.cacheDir)
        val vSize: Long
        try {
            vSize = ctx.contentResolver.openInputStream(videoUri)?.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
                tmp.length()
            } ?: throw IllegalStateException("视频读取失败")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        if (vSize <= 0) { tmp.delete(); throw IllegalStateException("视频内容为空") }
        return composeToGallery(ctx, jpeg, tmp, vSize, baseName, livePhotoId = null)
    }

    fun composeToGallery(ctx: Context, jpeg: ByteArray, videoFile: File, baseName: String = "IMG_", livePhotoId: String? = null): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }
        val vSize = videoFile.length()
        if (vSize <= 0) throw IllegalStateException("视频内容为空")
        return composeToGallery(ctx, jpeg, videoFile, vSize, baseName, livePhotoId)
    }

    private fun composeToGallery(ctx: Context, jpeg: ByteArray, tmp: File, vSize: Long, baseName: String, livePhotoId: String?): String {

        val id = livePhotoId?.takeIf { it.length == 28 } ?: VivoLivePhoto.generateId()
        val jpgTail = VivoLivePhoto.jpgTail(id)
        val mp4Tail = VivoLivePhoto.mp4Tail(id)

        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = "$baseName$now"
        val jpgName = "$base.jpg"

        val taken = System.currentTimeMillis()

        val jpegWithExif = withVivoExif(ctx, jpeg)

        var attempt = 0
        while (true) {
            val jpgUri = insertImage(ctx, jpgName, jpegWithExif, jpgTail, taken)
            var videoUri: Uri? = null
            try {
                videoUri = insertCompanionVideo(ctx, "$base.mp4", tmp, mp4Tail, taken)
                verifyTail(ctx, jpgUri, (jpegWithExif.size + jpgTail.size).toLong())

                if (Build.VERSION.SDK_INT >= 29) videoUri?.let { vu ->
                    ctx.contentResolver.update(vu, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (e: Throwable) {

                runCatching { ctx.contentResolver.delete(jpgUri, null, null) }
                if (videoUri != null) runCatching { ctx.contentResolver.delete(videoUri, null, null) }
                if (++attempt >= 2) {

                    tmp.delete()
                    throw IllegalStateException("动态照片写入失败（已重试）：${e.message}")
                }
                DebugLog.log("WRITER", "写入自检失败，重建一次：${e.message}")
                continue
            }
            tmp.delete()
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(jpgUri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return jpgName
        }
    }

    private fun withVivoExif(ctx: Context, jpeg: ByteArray): ByteArray {

        return runCatching {
            val tmp = File.createTempFile("lgm_exif", ".jpg", ctx.cacheDir)
            try {
                tmp.writeBytes(jpeg)
                val ex = ExifInterface(tmp.absolutePath)
                ex.setAttribute(ExifInterface.TAG_MAKE, "vivo")
                ex.setAttribute(ExifInterface.TAG_MODEL,
                    DeviceNames.marketName().takeIf { it.isNotBlank() } ?: "vivo iQOO Neo")
                val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                ex.setAttribute(ExifInterface.TAG_DATETIME, now)
                ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)

                ex.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                ex.saveAttributes()
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }.getOrDefault(jpeg)
    }

    /** Store the pair in DCIM/Camera with a shared stem; gallery pairing is device-dependent. */
    private fun legacyPath(name: String): String =
        File(File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }, name).absolutePath

    private fun insertImage(ctx: Context, name: String, jpeg: ByteArray, tail: ByteArray, taken: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            } else {
                // Android 9 legacy path: keep the still and companion video in the same directory.
                put(MediaStore.MediaColumns.DATA, legacyPath(name))
            }
        }
        val uri: Uri = ctx.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("相册写入失败")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { s: OutputStream ->
                s.write(jpeg)
                s.write(tail)
                s.flush()
            } ?: throw IllegalStateException("相册打开失败")
        } catch (e: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** Write the paired MP4 and observed private payload; gallery pairing remains device-dependent. */
    private fun insertCompanionVideo(ctx: Context, name: String, tmp: File, tail: ByteArray, taken: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            } else {
                // Android 9 legacy path: keep both files in the same directory.
                put(MediaStore.MediaColumns.DATA, legacyPath(name))
            }
        }
        val uri: Uri = ctx.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("视频写入失败")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { s: OutputStream ->
                tmp.inputStream().use { ins -> ins.copyTo(s) }
                s.write(tail)
                s.flush()
            } ?: throw IllegalStateException("视频打开失败")
        } catch (e: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }

        return uri
    }

    private fun verifyTail(ctx: Context, uri: Uri, minBytes: Long) {
        var ok = false
        var total = -1L
        var tailHex = ""
        runCatching {

            for (attempt in 0 until 40) {
                total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

                if (total >= minBytes) break
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            if (total >= minBytes) {

                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val skip = total - VivoLivePhoto.TRAILER.size
                    var skipped = 0L
                    while (skipped < skip) {
                        val n = ins.skip(skip - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    val buf = ByteArray(VivoLivePhoto.TRAILER.size)
                    java.io.DataInputStream(ins).readFully(buf)
                    if (buf.contentEquals(VivoLivePhoto.TRAILER)) ok = true
                    tailHex = buf.joinToString("") { "%02x".format(it) }
                }
            }
        }
        if (!ok) {
            DebugLog.log("WRITER", "verifyTail 失败 len=$total expect>=$minBytes tail=$tailHex")
            throw IllegalStateException("视频未写入")
        }
    }

    fun compose(jpeg: ByteArray, video: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val vSize = video.size.toLong()
        if (vSize <= 0L) return null

        var total = jpeg.size + vSize
        var xmpBytes = String.format(java.util.Locale.US, XMP_TEMPLATE, vSize, total).toByteArray(Charsets.UTF_8)
        var app1Len = 2 + XMP_ID.length + xmpBytes.size
        var prev = -1
        while (app1Len != prev) {
            prev = app1Len
            total = jpeg.size + 2L + app1Len + vSize
            xmpBytes = String.format(java.util.Locale.US, XMP_TEMPLATE, vSize, total).toByteArray(Charsets.UTF_8)
            app1Len = 2 + XMP_ID.length + xmpBytes.size
        }

        var insertAt = 2
        var i = 2
        while (i + 4 <= jpeg.size) {
            val marker = (jpeg[i].toInt() and 0xFF) shl 8 or (jpeg[i + 1].toInt() and 0xFF)
            if (marker in 0xFFE0..0xFFEF) {
                val segLen = (jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF)
                if (segLen < 2 || i + 2 + segLen > jpeg.size) return null
                i += 2 + segLen
                insertAt = i
            } else {
                insertAt = i
                break
            }
        }
        if (insertAt < 2 || insertAt > jpeg.size) return null

        val xmpIdBytes = XMP_ID.toByteArray(Charsets.UTF_8)
        val out = ByteArray(jpeg.size + 2 + app1Len + video.size)
        var p = 0
        System.arraycopy(jpeg, 0, out, p, insertAt); p += insertAt
        out[p++] = 0xFF.toByte(); out[p++] = 0xE1.toByte()
        out[p++] = ((app1Len shr 8) and 0xFF).toByte()
        out[p++] = (app1Len and 0xFF).toByte()
        System.arraycopy(xmpIdBytes, 0, out, p, xmpIdBytes.size); p += xmpIdBytes.size
        System.arraycopy(xmpBytes, 0, out, p, xmpBytes.size); p += xmpBytes.size
        System.arraycopy(jpeg, insertAt, out, p, jpeg.size - insertAt); p += jpeg.size - insertAt
        System.arraycopy(video, 0, out, p, video.size)
        return out
    }

    fun composeGoogleToGallery(ctx: Context, jpeg: ByteArray, videoFile: File, baseName: String = "IMG_"): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }
        val vSize = videoFile.length()
        if (vSize <= 0) throw IllegalStateException("视频内容为空")

        var total = jpeg.size + vSize
        var xmpBytes = String.format(java.util.Locale.US, XMP_TEMPLATE, vSize, total).toByteArray(Charsets.UTF_8)
        var app1Len = 2 + XMP_ID.length + xmpBytes.size
        var prev = -1
        while (app1Len != prev) {
            prev = app1Len
            total = jpeg.size + 2L + app1Len + vSize
            xmpBytes = String.format(java.util.Locale.US, XMP_TEMPLATE, vSize, total).toByteArray(Charsets.UTF_8)
            app1Len = 2 + XMP_ID.length + xmpBytes.size
        }

        var insertAt = 2
        var i = 2
        while (i + 4 <= jpeg.size) {
            val marker = (jpeg[i].toInt() and 0xFF) shl 8 or (jpeg[i + 1].toInt() and 0xFF)
            if (marker in 0xFFE0..0xFFEF) {
                val segLen = (jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF)
                if (segLen < 2 || i + 2 + segLen > jpeg.size) throw IllegalStateException("JPEG 段损坏")
                i += 2 + segLen
                insertAt = i
            } else {
                insertAt = i
                break
            }
        }
        if (insertAt < 2 || insertAt > jpeg.size) throw IllegalStateException("JPEG 段损坏")
        val xmpBlock = 2 + app1Len
        val finalSize = (jpeg.size + xmpBlock + vSize).toLong()
        // Write and verify the XMP-declared byte extent before making the item visible.
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = "$baseName$now"
        val name = "$base.MP.jpg"
        var attempt = 0
        while (true) {
            val uri = insertGoogleImage(ctx, name, jpeg, insertAt, app1Len, xmpBytes, videoFile)
            try {
                verifyGoogleTail(ctx, uri, finalSize)
            } catch (e: Throwable) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                if (++attempt >= 2) {
                    throw IllegalStateException("动态照片写入失败（已重试）：${e.message}")
                }
                DebugLog.log("WRITER", "Google 单文件自检失败，重建一次：${e.message}")
                continue
            }
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return name
        }
    }

    /**
     * Recovery output when a gallery-specific Motion Photo representation cannot be committed.
     * Saves a plain JPEG cover and the already-watermarked MP4 as two ordinary media items.
     * The pair shares a filename stem and timestamp, but no gallery pairing behavior is promised.
     */
    fun saveStaticAndVideoToGallery(
        ctx: Context, jpeg: ByteArray, videoFile: File, baseName: String = "IMG_"
    ): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }
        if (!videoFile.isFile || videoFile.length() <= 0L) throw IllegalStateException("视频内容为空")
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val stem = "$baseName$now"
        val imageName = "$stem.jpg"
        val videoName = "$stem.mp4"
        val taken = System.currentTimeMillis()
        var imageUri: Uri? = null
        var videoUri: Uri? = null
        try {

            val jpegWithExif = withVivoExif(ctx, jpeg)
            val writtenImageUri = insertPlainImage(ctx, imageName, jpegWithExif, taken)
            imageUri = writtenImageUri
            val writtenVideoUri = insertPlainVideo(ctx, videoName, videoFile, taken)
            videoUri = writtenVideoUri
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(writtenVideoUri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                ctx.contentResolver.update(writtenImageUri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            } else {
                android.media.MediaScannerConnection.scanFile(ctx,
                    arrayOf(legacyPath(imageName), legacyPath(videoName)),
                    arrayOf("image/jpeg", "video/mp4"), null)
            }
            return "$imageName + $videoName"
        } catch (t: Throwable) {
            videoUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            imageUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            throw IllegalStateException("动态照片降级文件写入失败：${t.message}", t)
        }
    }

    private fun insertPlainImage(ctx: Context, name: String, jpeg: ByteArray, taken: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else put(MediaStore.MediaColumns.DATA, legacyPath(name))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("静态封面创建失败")
        try {
            ctx.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(jpeg)
                output.flush()
            } ?: throw IllegalStateException("静态封面写入失败")
            return uri
        } catch (t: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun insertPlainVideo(ctx: Context, name: String, videoFile: File, taken: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else put(MediaStore.MediaColumns.DATA, legacyPath(name))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("配套视频创建失败")
        try {
            ctx.contentResolver.openOutputStream(uri, "w")?.use { output ->
                videoFile.inputStream().use { it.copyTo(output) }
                output.flush()
            } ?: throw IllegalStateException("配套视频写入失败")
            return uri
        } catch (t: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun insertGoogleImage(
        ctx: Context, name: String, jpeg: ByteArray, insertAt: Int,
        app1Len: Int, xmpBytes: ByteArray, videoFile: File
    ): Uri {
        val taken = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            } else {
                put(MediaStore.MediaColumns.DATA,
                    File(File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DCIM), "Camera"), name).absolutePath)
            }
        }
        val uri: Uri = ctx.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("相册写入失败")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { s: OutputStream ->
                s.write(jpeg, 0, insertAt)
                s.write(0xFF.toInt()); s.write(0xE1.toInt())
                s.write((app1Len shr 8) and 0xFF); s.write(app1Len and 0xFF)
                s.write(XMP_ID.toByteArray(Charsets.UTF_8))
                s.write(xmpBytes)
                s.write(jpeg, insertAt, jpeg.size - insertAt)
                videoFile.inputStream().use { ins -> ins.copyTo(s) }
                s.flush()
            } ?: throw IllegalStateException("相册打开失败")
        } catch (e: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun verifyGoogleTail(ctx: Context, uri: Uri, minBytes: Long) {
        var ok = false
        var total = -1L
        var tailHex = ""
        runCatching {
            for (attempt in 0 until 40) {
                total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (total >= minBytes) break
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            if (total >= minBytes) {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val skip = total - 4
                    var skipped = 0L
                    while (skipped < skip) {
                        val n = ins.skip(skip - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    val buf = ByteArray(4)
                    java.io.DataInputStream(ins).readFully(buf)
                    run {
                        tailHex = buf.joinToString("") { "%02x".format(it) }

                        ok = !(buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte() &&
                            buf[2] == 0xFF.toByte() && buf[3] == 0xD9.toByte())
                    }
                }
            }
        }
        if (!ok) {
            DebugLog.log("WRITER", "verifyGoogleTail 失败 len=$total expect>=$minBytes tail=$tailHex")
            throw IllegalStateException("视频未写入")
        }
    }

    /** Test/diagnostic helper: return the XMP template so JVM tests can verify its declared values. */
    fun formatXmpForTest(videoSize: Long, totalLen: Long): String =
        String.format(java.util.Locale.US, XMP_TEMPLATE, videoSize, totalLen)
}
