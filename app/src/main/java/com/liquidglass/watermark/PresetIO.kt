package com.liquidglass.watermark

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 用户预设：把当前水印/玻璃参数序列化为 JSON，可本地保存、分享给他人、从文件导入。
 * 用反射遍历 Params 全部 @JvmField（Int/Float/Boolean/String），新增参数自动纳入，无需逐字段维护。
 */
object PresetIO {
    private const val MAGIC = "LiquidGlassWatermarkPreset"
    private const val VERSION = 1

    private fun paramFields() = EditorState.Params::class.java.declaredFields

    /** 单个 Params → JSON 对象 */
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

    /** JSON 对象 → 应用到 [dst]（类型不匹配/缺字段自动跳过，绝不崩） */
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

    /** 导出当前方案为可分享的 JSON 文本 */
    fun exportCurrent(): String {
        val root = JSONObject()
        root.put("app", MAGIC)
        root.put("version", VERSION)
        root.put("plan", EditorState.activePlan)
        root.put("params", writeParams(EditorState.params))
        return root.toString(2)
    }

    /**
     * 导入预设文本：写入当前激活方案。机型/型号保留模板值，时间刷新为当前（避免沿用他人旧时间）。
     * @return 成功/失败
     */
    fun import(ctx: Context, text: String): Boolean {
        return runCatching {
            val root = JSONObject(text)
            if (root.optString("app") != MAGIC) return@runCatching false
            val pjo = root.optJSONObject("params") ?: return@runCatching false
            val tmp = EditorState.Params()
            readParams(tmp, pjo)
            // 统一扭曲一致性：新版预设带 warp → 由 warp 推导边缘/内部扭曲；
            // 旧预设只有 edgeWarp/innerWarp → 取较大者迁移到 warp 再统一推导
            if (pjo.has("warp")) tmp.deriveWarp()
            else {
                tmp.warp = maxOf(tmp.edgeWarp, tmp.innerWarp).coerceIn(0, 100)
                tmp.deriveWarp()
            }
            // layoutMode 必须与当前激活方案一致（方案1/2 布局固定，避免导入后布局错乱）
            tmp.layoutMode = EditorState.params.layoutMode
            tmp.darkMode = com.liquidglass.watermark.darkModeState
            tmp.dateText = EditorState.nowString()
            tmp.sanitizeInPlace()
            EditorState.params.copyFrom(tmp)
            EditorState.persist(ctx)
            true
        }.getOrDefault(false)
    }

    private fun presetDir(ctx: Context): File =
        File(ctx.filesDir, "presets").apply { runCatching { mkdirs() } }

    private fun safeName(name: String): String =
        name.trim().filter { it.isLetterOrDigit() || it in "_- .()" }.ifBlank { "preset" }

    /** 保存当前方案为本地预设（同名覆盖） */
    fun saveLocal(ctx: Context, name: String): Boolean = runCatching {
        val f = File(presetDir(ctx), safeName(name) + ".json")
        f.writeText(exportCurrent())
        true
    }.getOrDefault(false)

    /** 列出本地预设名（不含扩展名），按修改时间倒序 */
    fun listLocal(ctx: Context): List<String> =
        runCatching {
            presetDir(ctx).listFiles { f -> f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 应用本地预设到当前方案 */
    fun loadLocal(ctx: Context, name: String): Boolean = runCatching {
        val f = File(presetDir(ctx), safeName(name) + ".json")
        if (!f.exists()) false else import(ctx, f.readText())
    }.getOrDefault(false)

    /** 删除本地预设 */
    fun deleteLocal(ctx: Context, name: String): Boolean = runCatching {
        File(presetDir(ctx), safeName(name) + ".json").delete()
    }.getOrDefault(false)

    /** 写出供 FileProvider 分享的预设文件（cacheDir/share） */
    fun shareFile(ctx: Context, name: String = "my_preset"): File {
        val dir = File(ctx.cacheDir, "share").apply { runCatching { mkdirs() } }
        val f = File(dir, "liquidglass_${safeName(name)}.lgp.json")
        f.writeText(exportCurrent())
        return f
    }
}
