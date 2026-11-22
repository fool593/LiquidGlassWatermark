package com.liquidglass.watermark

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import java.util.Locale

object GeoUtils {

    fun readExifGeo(context: Context, uri: Uri?): String? =
        readExifLatLng(context, uri)?.let { formatCoord(it.first, it.second) }

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

    fun readExifGeoPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val exif = ExifInterface(path)
            val latLng = FloatArray(2)
            if (exif.getLatLong(latLng)) formatCoord(latLng[0].toDouble(), latLng[1].toDouble()) else null
        } catch (t: Throwable) { null }
    }

    fun formatCoord(lat: Double, lng: Double): String {
        val ns = if (lat >= 0.0) "N" else "S"
        val ew = if (lng >= 0.0) "E" else "W"
        return String.format(
            Locale.US, "%s%.4f, %s%.4f",
            ns, kotlin.math.abs(lat), ew, kotlin.math.abs(lng)
        )
    }

    private fun formatCoord(lat: Float, lng: Float): String = formatCoord(lat.toDouble(), lng.toDouble())

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

    private fun formatPlace(addr: Address): String? {
        val parts = ArrayList<String>(4)
        fun add(s: String?) {
            val v = s?.trim().orEmpty()
            if (v.isEmpty()) return
            if (parts.isNotEmpty() && (parts.last().endsWith(v) || v.endsWith(parts.last()))) return
            parts.add(v)
        }
        add(addr.adminArea)
        add(addr.locality)
        add(addr.subAdminArea)
        add(addr.subLocality)
        add(addr.thoroughfare)
        add(addr.featureName.takeIf { it != addr.thoroughfare && it != addr.subLocality })
        if (parts.isEmpty()) return null
        return parts.joinToString("").removePrefix("中国").trim().ifBlank { null }
    }

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
                } catch (t: Throwable) {  }
            }
            null
        } catch (t: Throwable) { null }
    }
}
