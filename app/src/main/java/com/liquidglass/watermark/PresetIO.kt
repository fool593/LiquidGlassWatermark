package com.liquidglass.watermark

import android.content.Context
import org.json.JSONObject
import java.io.File

object PresetIO {
    private const val MAGIC = "LiquidGlassWatermarkPreset"
    private const val VERSION = 1

    private fun paramFields() = EditorState.Params::class.java.declaredFields

    private fun writeParams(p: EditorState.Params): JSONObject {
        val jo = JSONObject()
        paramFields().forEach { f ->
            runCatching {
                f.isAccessible = true
                when (val v = f.get(p)) {
                    is Int -> jo.put(f.name, v)
                    is Float -> jo.put(f.name, v.toDouble())
                    is Boolean -> jo.put(f.name, v)
                    is String -> jo.put(f.name, v)
                    else -> {}
                }
            }
        }
        return jo
    }

    private fun readParams(dst: EditorState.Params, jo: JSONObject) {
        paramFields().forEach { f ->
            runCatching {
                if (!jo.has(f.name)) return@runCatching
                f.isAccessible = true
                when (f.type) {
                    Int::class.javaPrimitiveType -> f.setInt(dst, jo.getInt(f.name))
                    Float::class.javaPrimitiveType -> {
                        val value = jo.getDouble(f.name).toFloat()
                        // JSON permits very large exponents: 1e300 becomes Infinity
                        // when converted to Float even though the Double was finite.
                        if (value.isFinite()) f.setFloat(dst, value)
                    }
                    Boolean::class.javaPrimitiveType -> f.setBoolean(dst, jo.getBoolean(f.name))
                    String::class.java -> f.set(dst, jo.getString(f.name))
                }
            }
        }
    }

    fun exportCurrent(): String {
        val root = JSONObject()
        root.put("app", MAGIC)
        root.put("version", VERSION)
        root.put("plan", EditorState.activePlan)
        root.put("params", writeParams(EditorState.params))
        return root.toString(2)
    }

    fun import(ctx: Context, text: String): Boolean {
        return runCatching {
            val root = JSONObject(text)
            if (root.optString("app") != MAGIC) return@runCatching false
            val pjo = root.optJSONObject("params") ?: return@runCatching false
            val tmp = EditorState.Params()

            tmp.customLogos.addAll(EditorState.params.customLogos)
            readParams(tmp, pjo)

            if (pjo.has("warp")) tmp.deriveWarp()
            else {
                tmp.warp = maxOf(tmp.edgeWarp, tmp.innerWarp).coerceIn(0, 100)
                tmp.deriveWarp()
            }

            tmp.layoutMode = EditorState.params.layoutMode
            tmp.darkMode = com.liquidglass.watermark.darkModeState
            tmp.dateText = EditorState.nowString()
            tmp.sanitizeInPlace()

            EditorState.modelCustom = true
            EditorState.modelCodeCustom = true
            EditorState.params.copyFrom(tmp)
            EditorState.persist(ctx)
            true
        }.getOrDefault(false)
    }

    private fun presetDir(ctx: Context): File =
        File(ctx.filesDir, "presets").apply { runCatching { mkdirs() } }

    private fun safeName(name: String): String =
        name.trim().filter { it.isLetterOrDigit() || it in "_- .()" }.ifBlank { "preset" }

    fun saveLocal(ctx: Context, name: String): Boolean = runCatching {
        val f = File(presetDir(ctx), safeName(name) + ".json")
        f.writeText(exportCurrent())
        true
    }.getOrDefault(false)

    fun listLocal(ctx: Context): List<String> =
        runCatching {
            presetDir(ctx).listFiles { f -> f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        }.getOrDefault(emptyList())

    fun loadLocal(ctx: Context, name: String): Boolean = runCatching {
        val f = File(presetDir(ctx), safeName(name) + ".json")
        if (!f.exists()) false else import(ctx, f.readText())
    }.getOrDefault(false)

    fun deleteLocal(ctx: Context, name: String): Boolean = runCatching {
        File(presetDir(ctx), safeName(name) + ".json").delete()
    }.getOrDefault(false)

    fun shareFile(ctx: Context, name: String = "my_preset"): File {
        val dir = File(ctx.cacheDir, "share").apply { runCatching { mkdirs() } }
        val f = File(dir, "liquidglass_${safeName(name)}.lgp.json")
        f.writeText(exportCurrent())
        return f
    }
}
