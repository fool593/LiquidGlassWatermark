package com.liquidglass.watermark

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import java.util.Locale

/**
 * v15.35 地理位置：EXIF / 当前定位 → 真实地名（省 市 区县 街道/村），失败的坐标保留为选项。
 * 输出示例：湖南省永州市新田县中山街道；经纬度格式："N25.9000, E112.2000"。
 */
object GeoUtils {

    /** 从 content/文件 uri 读取照片 EXIF GPS，成功返回纬度/经度文本 */
    fun readExifGeo(context: Context, uri: Uri?): String? =
        readExifLatLng(context, uri)?.let { formatCoord(it.first, it.second) }

    /** 从 content uri 读取 EXIF GPS 原始坐标（用于再反查地名 / 经纬度切换） */
    fun readExifLatLng(context: Context, uri: Uri?): Pair<Double, Double>? {
        if (uri == null) return null
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use {
                val exif = try { ExifInterface(it) } catch (t: Throwable) { return null }
                val latLng = FloatArray(2)
                if (exif.getLatLong(latLng)) latLng[0].toDouble() to latLng[1].toDouble() else null
            }
        } catch (t: Throwable) { null }
    }

    /** 从本地文件路径读取 EXIF GPS（fallback 路径，如缓存副本） */
    fun readExifGeoPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val exif = ExifInterface(path)
            val latLng = FloatArray(2)
            if (exif.getLatLong(latLng)) formatCoord(latLng[0].toDouble(), latLng[1].toDouble()) else null
        } catch (t: Throwable) { null }
    }

    /** 纬度/经度 → 显示文本：方向字母 + 4 位小数 */
    fun formatCoord(lat: Double, lng: Double): String {
        val ns = if (lat >= 0.0) "N" else "S"
        val ew = if (lng >= 0.0) "E" else "W"
        return String.format(
            Locale.US, "%s%.4f, %s%.4f",
            ns, kotlin.math.abs(lat), ew, kotlin.math.abs(lng)
        )
    }

    private fun formatCoord(lat: Float, lng: Float): String = formatCoord(lat.toDouble(), lng.toDouble())

    /**
     * 坐标 → 中文地名（省 + 市 + 区县 + 街道/镇/村）。
     * Geocoder 不可用 / 无网络 / 无匹配时返回 null，由调用方回退到经纬度文本。
     * 必须在后台线程调用（Geocoder 是阻塞的）。
     */
    fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 1)
            val addr = list?.firstOrNull() ?: return null
            formatPlace(addr)
        } catch (t: Throwable) { null }
    }

    /** 按 省→市→区县→街道/镇→村/路 顺序拼接，去重并去掉“中国”前缀 */
    private fun formatPlace(addr: Address): String? {
        val parts = ArrayList<String>(4)
        fun add(s: String?) {
            val v = s?.trim().orEmpty()
            if (v.isEmpty()) return
            if (parts.isNotEmpty() && (parts.last().endsWith(v) || v.endsWith(parts.last()))) return
            parts.add(v)
        }
        add(addr.adminArea)                 // 湖南省
        add(addr.locality)                  // 永州市
        add(addr.subAdminArea)              // 新田县
        add(addr.subLocality)               // 中山街道
        add(addr.thoroughfare)              // 支路名（有则补上）
        add(addr.featureName.takeIf { it != addr.thoroughfare && it != addr.subLocality }) // 潭田村
        if (parts.isEmpty()) return null
        return parts.joinToString("").removePrefix("中国").trim().ifBlank { null }
    }

    /** 用 LocationManager 取最近已知位置（可能为 null）。需已授权位置权限 */
    fun lastKnown(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (p in providers) {
                try {
                    lm.getLastKnownLocation(p)?.let { return it }
                } catch (t: Throwable) { /* 无权限/无该 provider，继续 */ }
            }
            null
        } catch (t: Throwable) { null }
    }
}
