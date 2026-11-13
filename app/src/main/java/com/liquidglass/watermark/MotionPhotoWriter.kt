package com.liquidglass.watermark

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * 动态照片（Motion Photo / Live Photo 风格）单文件合成器。
 *
 * Google Motion Photo 1.0-style single-file writer:
 * one .jpg contains a JPEG cover, XMP metadata and a trailing MP4. OEM gallery recognition is not
 * inferred from this container and must be checked on each device and system gallery version.
 *
 * XMP 双命名空间（权威格式，见 Google 相机真实输出）：
 *  - Container（http://ns.google.com/photos/1.0/container/）：Directory 数组，
 *    第 1 项 Primary（image/jpeg），最后 1 项 MotionPhoto（video/mp4 + Length=视频字节数）
 *  - GCamera（http://ns.google.com/photos/1.0/camera/）：MotionPhoto=1、MotionPhotoVersion=1、
 *    MotionPhotoPresentationTimestampUs（无对应帧写 -1）
 *
 * 文件名遵循官方命名规范：XXX.MP.jpg（MP 标志动态照片）。
 * 系统相册识别 XMP 后：列表显示封面图片，点开长按播放内嵌视频。
 */
object MotionPhotoWriter {

    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"
    // 严格遵循 Android Motion Photo 1.0 官方规范：
    //  - 三个独立命名空间：camera(GCamera) / container(Container) / container/item(Item)，Item 必须单独声明
    //  - Item:Mime/Semantic/Length/Padding 属于 item 命名空间（不能写成 Container:Item:Xxx，否则前缀未声明、解析失败）
    //  - 相机字段 MotionPhoto=1 + Version=1 + PresentationTimestampUs；保留旧 MicroVideo* 供老读取器兼容
    // 参数：%1\$d=视频字节数（Item:Length 与 MicroVideoOffset） %2\$d=文件总字节数（MotionPhotoVideoEnd）
    // Besides the Google namespace, this template carries observed Oplus/Xiaomi namespace fields as
    // compatibility hints. Their presence is not a statement that any current OEM gallery accepts
    // this output; those combinations require physical-device verification.
    // L8 注意：这些未在目标设备验证过的 namespace（OpCamera/MiCamera）可能污染第三方解析器
    // （部分解析器见到未知命名空间会整体判为非标准动图）。若某设备识别异常，优先怀疑此处并按机型裁剪。
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

    /**
     * Streams a vivo-style paired JPEG/MP4 representation to DCIM/Camera. The payload layout is
     * based on observed samples and is not a vendor-published compatibility guarantee.
     * 视频不整读内存：先流式落临时文件拿字节数，再拼写入相册。
     *
     * @param jpeg 封面 JPEG 字节（必须 SOI/EOI 完整；PNG 等请先转 JPEG）
     * @param videoUri 视频 Uri（content://，H.264 兼容性最佳）
     * @return 相册显示文件名（IMG_xxx.jpg）
     */
    fun composeToGallery(ctx: Context, jpeg: ByteArray, videoUri: Uri, baseName: String = "IMG_"): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }
        // 1) 视频流式落临时文件，拿准确字节数
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

    /** File 版重载：视频已含水印转码结果，直接以现有字节数合成（避免二次拷贝） */
    fun composeToGallery(ctx: Context, jpeg: ByteArray, videoFile: File, baseName: String = "IMG_", livePhotoId: String? = null): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("封面不是有效 JPEG")
        }
        val vSize = videoFile.length()
        if (vSize <= 0) throw IllegalStateException("视频内容为空")
        return composeToGallery(ctx, jpeg, videoFile, vSize, baseName, livePhotoId)
    }

    private fun composeToGallery(ctx: Context, jpeg: ByteArray, tmp: File, vSize: Long, baseName: String, livePhotoId: String?): String {
        // 2) live_photo_id：优先复用原动态照片 ID（保留关联信息），否则生成新 ID（两个文件共用）
        val id = livePhotoId?.takeIf { it.length == 28 } ?: VivoLivePhoto.generateId()
        val jpgTail = VivoLivePhoto.jpgTail(id)
        val mp4Tail = VivoLivePhoto.mp4Tail(id)

        // 3) 命名与 vivo 原生动态照片完全同构：IMG_yyyyMMdd_HHmmss.jpg/.mp4
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = "$baseName$now"
        val jpgName = "$base.jpg"
        // 两文件共用同一拍摄时刻（样本两文件 DATE_TAKEN 同刻，配对更稳）
        val taken = System.currentTimeMillis()
        // 4) 注入 vivo 相机 EXIF：真机样本（IMG_20260915_015441.jpg）含 Make=vivo、Model=vivo iQOO Neo、
        //    DateTime 三元组；vivo 相册先凭 EXIF 判定"相机照片"，缺失则动态照片配对不生效（此前"无效果"根因）
        val jpegWithExif = withVivoExif(ctx, jpeg)
        // 5) 写入 + 自检，最多整体重试 2 次：vivo MediaProvider 偶发 length 缓存未刷新/写入抖动，
        //    自检失败先清理半成品再重建（同名同 ID），绝不把"只有封面"当成功交付
        var attempt = 0
        while (true) {
            val jpgUri = insertImage(ctx, jpgName, jpegWithExif, jpgTail, taken)
            var videoUri: Uri? = null
            try {
                videoUri = insertCompanionVideo(ctx, "$base.mp4", tmp, mp4Tail, taken)
                verifyTail(ctx, jpgUri, (jpegWithExif.size + jpgTail.size).toLong())
                // 自检通过后统一放行：先让配套视频就绪，紧接着放封面——封面入库触发 vivo 同名配对时
                // 视频已可见且同 DATE_TAKEN，最大化被识别为单个动态照片的概率
                if (Build.VERSION.SDK_INT >= 29) videoUri?.let { vu ->
                    ctx.contentResolver.update(vu, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (e: Throwable) {
                // 清理本次全部半成品：主图 + 配套视频（insertCompanionVideo 内部失败已自删视频条目；
                // 若视频已成功但主图自检失败，必须一并删除已对用户可见的视频，防止相册堆积垃圾文件）
                runCatching { ctx.contentResolver.delete(jpgUri, null, null) }
                if (videoUri != null) runCatching { ctx.contentResolver.delete(videoUri, null, null) }
                if (++attempt >= 2) {
                    // 双文件配对是 vivo 相册识别动态照片的前提，写失败必须让主流程失败并明确提示，
                    // 不能再静默降级成"只有封面的静态图"
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

    /**
     * 给封面 JPEG 注入 vivo 相机 EXIF（Make/Model/DateTime 三元组）。
     * 依据真机样本逐字段核对：Make=vivo、Model="vivo iQOO Neo"、DateTime/Original/Digitized 同一时刻。
     * 用系统 ExifInterface 在原图上无损追加 APP1 段（不重编码像素）。
     */
    private fun withVivoExif(ctx: Context, jpeg: ByteArray): ByteArray {
        // M2：损坏/结构不完整的 JPEG 会让 ExifInterface 构造抛 IOException，进而被上层重试循环
        // 吞成"动态照片写入失败（已重试）"。EXIF 注入失败不应阻断交付——兜底返回原始 JPEG。
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
                // 封面像素已按 EXIF 转正，输出必须标记 normal，否则相册/播放器二次旋转（竖屏又变横屏）
                ex.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                ex.saveAttributes()
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }.getOrDefault(jpeg)
    }

    /** 写主图：JPEG + 尾部追加 vivo payload（vivo 格式，不插 Google XMP） */
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
        // 不在此处置 IS_PENDING=0：配套视频必须保持隐藏，直到封面也写完自检通过，
        // 由 composeToGallery 统一"先放视频、紧接着放封面"，避免孤立视频先入库被相册标记为普通视频、
        // 封面随后才可见而错过 vivo 的同名配对窗口（此前相册里显示成两个独立文件的关键时序问题）
        return uri
    }

    /** 自检：读回主图，确认 vivo 尾部真实存在（防"合成出来是纯静态图"） */
    private fun verifyTail(ctx: Context, uri: Uri, minBytes: Long) {
        var ok = false
        var total = -1L
        var tailHex = ""
        runCatching {
            // 部分 ROM（vivo/国产）MediaProvider 在 openOutputStream close 后不立即刷新 length 缓存，
            // 立即读回会得到旧长度（≤minBytes）→ 假"视频未写入"。带同步重试（最长 8 秒）。
            for (attempt in 0 until 40) {
                total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                // 写入完全成功时文件长度恰好等于 minBytes（JPEG+尾部精确值），必须 >= 才算通过
                if (total >= minBytes) break
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            if (total >= minBytes) {
                // 长度够：再验文件尾部 11B trailer 魔数（比长度更可靠，长度缓存错时也不误杀）
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

    /**
     * Google Motion Photo 1.0-style single-file output:
     * JPEG cover + XMP APP1 (GCamera/Container/Item) + trailing embedded MP4.
     * This writes the documented Google metadata shape; OEM gallery recognition is device-specific
     * and is not guaranteed by matching the XMP alone. Video bytes are streamed to MediaStore.
     *
     * @param jpeg 封面 JPEG（已渲染水印）
     * @param videoFile 视频文件（已逐帧渲染水印的 H.264 MP4）
     * @return 相册显示文件名（IMG_xxx.MP.jpg）
     */
    /**
     * 纯字节合成 Google 单文件动态照片：JPEG 封面（APP 段后插入 XMP APP1）+ 尾部 MP4。
     * 不碰 Context/相册，供单测与需要字节结果的路径复用；写相册走 [composeGoogleToGallery]。
     * @return 合成字节；封面非 JPEG / 视频为空 / JPEG 段损坏时返回 null
     */
    fun compose(jpeg: ByteArray, video: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val vSize = video.size.toLong()
        if (vSize <= 0L) return null
        // XMP 双参迭代收敛（与 composeGoogleToGallery 同一算法）
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
        // JPEG APP 段插入点（APP 段之后、SOF 之前）
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
        // 拼装：JPEG 头 + APP1(标志+长度+XMP_ID+XMP) + JPEG 尾 + MP4
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
        // XMP 双参（视频字节数 + 文件总长）：先近似后迭代收敛，保证 VideoEnd 精确
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
        // 找 JPEG 段插入点（APP 段之后、SOF 之前）
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
            // M5：回退路径此前直接写原始 jpeg（未注 vivo EXIF），与主路径不一致，vivo 相册可能不认。
            // 与 composeToGallery 保持一致，先注 EXIF 再落盘。
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

    /** 流式写 Google 单文件：JPEG 头 + XMP + JPEG 尾 + 视频，全程不整读视频进内存 */
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

    /** Google 单文件自检：长度 >= 预期 + 尾部不是 JPEG 数据（防"静态图"交付，误杀率≈0） */
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
                        // 尾部必须是 MP4 数据：JPEG 以 FFD8 开头、FFD9 结尾，视频数据中不应出现 FFD8FFD9
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
