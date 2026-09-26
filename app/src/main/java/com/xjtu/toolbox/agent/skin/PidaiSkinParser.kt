package com.xjtu.toolbox.agent.skin

import com.xjtu.toolbox.util.isBoolean
import com.xjtu.toolbox.util.isNumber
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.doubleValue
import com.xjtu.toolbox.util.booleanValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isPrimitive
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.agent.bot.Disc
import com.xjtu.toolbox.agent.bot.PROFILE_SAMPLES
import com.xjtu.toolbox.agent.bot.Point
import com.xjtu.toolbox.agent.bot.SkinOutline
import com.xjtu.toolbox.agent.bot.SkinPathParser
import com.xjtu.toolbox.agent.bot.SkinPathSyntaxException
import com.xjtu.toolbox.agent.bot.normalizeRadii
import com.xjtu.toolbox.agent.bot.outlineFromEllipse
import com.xjtu.toolbox.agent.bot.outlineFromPoints
import com.xjtu.toolbox.agent.bot.outlineFromRadii
import com.xjtu.toolbox.agent.bot.outlineFromRect
import com.xjtu.toolbox.agent.bot.profileFromPolygon
import com.xjtu.toolbox.agent.bot.unionOfCirclesProfile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.zip.ZipInputStream
import kotlin.math.PI

/**
 * 皮肤解析。
 *
 * `format_version: 2`（渲染器 `free-motion-v2`）是自由格式：任意条子路径、任意颜色、
 * 任意层数、可带位图，形状之间不再需要能被同一组射线采样。`format_version: 1`
 * （`radial-motion-v1`）继续可读，解析时被无损翻译成同一套自由图层，于是运行时
 * 只有一条代码路径。
 */
object PidaiSkinParser {
    private const val MAX_JSON_BYTES = 1_048_576
    private const val MAX_IMAGE_BYTES = 4 * 1_048_576
    private const val MAX_TOTAL_BYTES = 24 * 1_048_576
    private const val MAX_ENTRIES = 96
    private const val MAX_OUTLINE_SEGMENTS = 60_000

    const val RENDERER_V1 = "radial-motion-v1"
    const val RENDERER_V2 = "free-motion-v2"

    private val idRegex = Regex("^[a-z][a-z0-9_-]{0,63}$")
    private val layerKeyRegex = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val imagePathRegex = Regex("^images/[A-Za-z0-9_-]{1,64}\\.(png|jpg|jpeg|webp)$")
    private val jsonFiles = setOf("manifest.json", "motion.json", "persona.json")

    fun parseZip(input: InputStream): PidaiSkin {
        val files = linkedMapOf<String, ByteArray>()
        var entries = 0
        var total = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                failIf(entries > MAX_ENTRIES, "皮肤包文件过多")
                val name = entry.name.replace('\\', '/')
                failIf(name.startsWith('/') || name.split('/').any { it == ".." }, "皮肤包含有不安全路径")
                if (!entry.isDirectory) {
                    val isImage = imagePathRegex.matches(name)
                    if (name in jsonFiles || isImage) {
                        val cap = if (isImage) MAX_IMAGE_BYTES else MAX_JSON_BYTES
                        failIf(entry.size > cap, "皮肤包中的 $name 过大")
                        val bytes = readLimited(zip, cap, name)
                        total += bytes.size
                        failIf(total > MAX_TOTAL_BYTES, "皮肤包解压后过大")
                        failIf(name in files, "皮肤包包含重复的 $name")
                        files[name] = bytes
                    }
                }
                zip.closeEntry()
            }
        }
        return parseFiles(files)
    }

    /**
     * motion.json 里引用到的位图路径。GitHub 导入先读这份清单，再按需拉取，
     * 不会去下载仓库里其他任何内容。
     */
    fun assetRefs(motionBytes: ByteArray): List<String> {
        val root = objectRoot(motionBytes, "motion.json")
        val shapes = root.optionalObject("shapes") ?: return emptyList()
        return shapes.entries.mapNotNull { (_, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.get("kind")?.stringValue != "image") return@mapNotNull null
            obj.optionalString("src")?.takeIf { imagePathRegex.matches(it) }
        }.distinct()
    }

    fun parseFiles(input: Map<String, ByteArray>): PidaiSkin {
        val files = input.filterKeys { it in jsonFiles || imagePathRegex.matches(it) }
        val manifestBytes = files["manifest.json"] ?: fail("缺少 manifest.json")
        val motionBytes = files["motion.json"] ?: fail("缺少 motion.json")
        files.forEach { (name, bytes) ->
            val cap = if (name in jsonFiles) MAX_JSON_BYTES else MAX_IMAGE_BYTES
            failIf(bytes.size > cap, "$name 过大")
        }
        failIf(files.values.sumOf { it.size } > MAX_TOTAL_BYTES, "皮肤总体积过大")

        val manifestJson = objectRoot(manifestBytes, "manifest.json")
        val id = manifestJson.string("id").validId("manifest.id")
        val formatVersion = manifestJson.int("format_version")
        failIf(formatVersion !in 1..2, "暂不支持此皮肤格式版本")
        val defaultRenderer = if (formatVersion == 1) RENDERER_V1 else RENDERER_V2
        val renderer = manifestJson.optionalString("renderer") ?: defaultRenderer
        failIf(renderer != defaultRenderer, "格式版本 $formatVersion 不接受渲染器 $renderer")
        val manifest = PidaiSkinManifest(
            formatVersion = formatVersion,
            id = id,
            name = manifestJson.string("name").limited("manifest.name", 80),
            version = manifestJson.string("version").limited("manifest.version", 40),
            author = manifestJson.optionalString("author")?.limited("manifest.author", 80).orEmpty(),
            description = manifestJson.optionalString("description")?.limited("manifest.description", 500).orEmpty(),
            renderer = renderer,
        )
        verifyHashes(manifestJson, files)
        val motion = parseMotion(objectRoot(motionBytes, "motion.json"), id, formatVersion, files)
        val persona = files["persona.json"]?.let { parsePersona(objectRoot(it, "persona.json"), motion) }
        return PidaiSkin(manifest, motion, persona, files, contentHash(files))
    }

    private fun parseMotion(
        root: JsonObject,
        skinId: String,
        formatVersion: Int,
        files: Map<String, ByteArray>,
    ): PidaiMotion {
        failIf(root.string("skin_id") != skinId, "motion.skin_id 与 manifest.id 不一致")
        // 底栏图标是个方框，整幅铺底必然露出方角。想要底板就自己画一层放在最下面。
        failIf(root.containsKey("background"), "motion.background 已移除：把底板画成最底下的一个图层")
        val shapesObject = root.objectValue("shapes")
        failIf(shapesObject.size !in 1..512, "motion.shapes 数量应为 1..512")
        var totalSegments = 0
        val shapes = shapesObject.entries.associate { (name, element) ->
            val shapeId = name.validId("shape id")
            val shape = if (formatVersion == 1) {
                PidaiShape(shapeId, outlineFromRadii(parseRadialShape(element.asObject("shape $name"), name)), null)
            } else {
                parseFreeShape(element.asObject("shape $name"), shapeId, files)
            }
            shape.outline?.let { totalSegments += it.subpaths.sumOf { sub -> sub.segments } }
            shapeId to shape
        }
        failIf(totalSegments > MAX_OUTLINE_SEGMENTS, "皮肤的轮廓段数过多")

        val actionsArray = root.array("actions")
        failIf(actionsArray.size !in 1..64, "motion.actions 数量应为 1..64")
        val actions = linkedMapOf<String, PidaiMotionAction>()
        actionsArray.forEachIndexed { index, element ->
            val obj = element.asObject("motion.actions[$index]")
            val id = obj.string("id").validId("action id")
            failIf(id in actions, "动作 id 重复：$id")
            val duration = obj.double("duration").finite("action.duration")
            failIf(duration <= 0.0 || duration > 60.0, "动作 $id 时长应在 0..60 秒")
            val frameArray = obj.array("frames")
            failIf(frameArray.size !in 2..256, "动作 $id 至少需要 2 个关键帧")
            val frames = frameArray.mapIndexed { frameIndex, frameElement ->
                val frame = frameElement.asObject("$id.frames[$frameIndex]")
                val label = "$id.frames[$frameIndex]"
                PidaiMotionFrame(
                    t = frame.double("t").finite("frame.t"),
                    ease = (frame.optionalString("ease") ?: "linear").validEase(),
                    layers = if (formatVersion == 1) {
                        radialLayers(frame, shapes, label)
                    } else {
                        freeLayers(frame, shapes, label)
                    },
                )
            }
            failIf(frames.first().t != 0.0, "动作 $id 的首帧必须从 0 开始")
            failIf(frames.zipWithNext().any { (a, b) -> b.t <= a.t }, "动作 $id 的关键帧时间必须递增")
            failIf(frames.last().t > duration + 1e-9, "动作 $id 的末帧超过 duration")
            failIf(frames.all { it.layers.isEmpty() }, "动作 $id 没有任何可绘制的图层")
            actions[id] = PidaiMotionAction(
                id = id,
                duration = duration,
                loop = obj.optionalBoolean("loop") ?: false,
                returnTo = obj.optionalString("return_to"),
                frames = frames,
            )
        }
        actions.values.forEach { action ->
            action.returnTo?.let { failIf(it !in shapes && it !in actions, "动作 ${action.id} 的 return_to 不存在") }
        }

        val bindings = root.optionalObject("bindings")?.entries?.associate { (beat, value) ->
            failIf(beat !in setOf("rest", "idle", "thinking", "alert", "tap"), "未知动作绑定：$beat")
            val action = value.stringValue
            failIf(action !in actions, "动作绑定 $beat 引用了不存在的动作 $action")
            beat to action
        }.orEmpty()
        val transitions = root.optionalArray("transitions")?.mapIndexed { index, element ->
            val obj = element.asObject("transitions[$index]")
            val from = obj.string("from")
            val to = obj.string("to")
            failIf(from !in actions || to !in actions, "转场 $from → $to 引用了不存在的动作")
            val duration = obj.double("duration").finite("transition.duration")
            failIf(duration < 0.0 || duration > 5.0, "转场时长应在 0..5 秒")
            PidaiTransition(from, to, duration, (obj.optionalString("ease") ?: "ease-out").validEase())
        }.orEmpty()
        return PidaiMotion(
            colorArgb = parseHexColor(root.optionalString("color"), "motion.color"),
            actions = actions,
            bindings = bindings,
            transitions = transitions,
        )
    }

    // ---- v2：自由图层 ----------------------------------------------------

    private fun freeLayers(frame: JsonObject, shapes: Map<String, PidaiShape>, label: String): List<PidaiLayer> {
        val array = frame.optionalArray("layers") ?: fail("$label 缺少 layers")
        failIf(array.size > 64, "$label 的图层数量应为 0..64")
        val keys = mutableSetOf<String>()
        return array.mapIndexed { index, element ->
            val obj = element.asObject("$label.layers[$index]")
            val shapeName = obj.string("shape")
            val shape = shapes[shapeName] ?: fail("$label 引用了不存在的形状 $shapeName")
            // 默认 key 带 '#'，用户写的 key 不允许，两者不会撞上。
            val declaredKey = obj.optionalString("key")?.also {
                failIf(!layerKeyRegex.matches(it), "$label.layers[$index].key 无效")
            }
            val key = declaredKey ?: "$shapeName#$index"
            failIf(!keys.add(key), "$label 的图层 key 重复：$key")
            PidaiLayer(
                key = key,
                shape = shape,
                cx = (obj.optionalDouble("cx") ?: 0.0).bounded("$label.cx", 100.0),
                cy = (obj.optionalDouble("cy") ?: 0.0).bounded("$label.cy", 100.0),
                sx = (obj.optionalDouble("sx") ?: 1.0).bounded("$label.sx", 100.0),
                sy = (obj.optionalDouble("sy") ?: 1.0).bounded("$label.sy", 100.0),
                rot = (obj.optionalDouble("rot_deg") ?: 0.0).bounded("$label.rot_deg", 100_000.0) / 180.0 * PI,
                pivotX = (obj.optionalDouble("pivot_x") ?: 0.0).bounded("$label.pivot_x", 100.0),
                pivotY = (obj.optionalDouble("pivot_y") ?: 0.0).bounded("$label.pivot_y", 100.0),
                alpha = (obj.optionalDouble("alpha") ?: 1.0).also {
                    failIf(it !in 0.0..1.0, "$label.layers[$index].alpha 应在 0..1")
                },
                fill = obj.optionalPaint("fill"),
                stroke = obj.optionalPaint("stroke"),
                strokeWidth = obj.optionalDouble("stroke_width")?.also {
                    failIf(it < 0.0 || it > 10.0, "$label.layers[$index].stroke_width 应在 0..10")
                },
            )
        }
    }

    private fun parseFreeShape(obj: JsonObject, id: String, files: Map<String, ByteArray>): PidaiShape {
        val kind = obj.string("kind")
        if (kind == "image") {
            val src = obj.string("src")
            failIf(!imagePathRegex.matches(src), "形状 $id 的 src 必须是 images/ 下的 png/jpg/webp")
            val bytes = files[src] ?: fail("皮肤包缺少图片 $src")
            failIf(!looksLikeImage(bytes), "$src 不是可识别的 PNG/JPEG/WebP")
            val w = obj.double("w").bounded("$id.w", 100.0)
            val h = obj.double("h").bounded("$id.h", 100.0)
            failIf(w <= 0.0 || h <= 0.0, "形状 $id 的 w/h 必须为正")
            return PidaiShape(id = id, outline = null, imageSrc = src, imageW = w, imageH = h)
        }
        val outline: SkinOutline = try {
            when (kind) {
                "path" -> SkinPathParser.parse(obj.string("d"))
                "polygon" -> outlineFromPoints(flatPoints(obj, id), closed = true)
                "polyline" -> outlineFromPoints(flatPoints(obj, id), closed = false)
                "circle" -> outlineFromEllipse(
                    obj.optionalDouble("cx") ?: 0.0,
                    obj.optionalDouble("cy") ?: 0.0,
                    obj.double("r").bounded("$id.r", 100.0),
                    obj.double("r").bounded("$id.r", 100.0),
                )
                "ellipse" -> outlineFromEllipse(
                    obj.optionalDouble("cx") ?: 0.0,
                    obj.optionalDouble("cy") ?: 0.0,
                    obj.double("rx").bounded("$id.rx", 100.0),
                    obj.double("ry").bounded("$id.ry", 100.0),
                )
                "rect" -> outlineFromRect(
                    obj.double("x").bounded("$id.x", 100.0),
                    obj.double("y").bounded("$id.y", 100.0),
                    obj.double("w").bounded("$id.w", 100.0),
                    obj.double("h").bounded("$id.h", 100.0),
                    (obj.optionalDouble("radius") ?: 0.0).bounded("$id.radius", 100.0),
                )
                else -> fail("形状 $id 的 kind 不受支持：$kind")
            }
        } catch (e: SkinPathSyntaxException) {
            fail("形状 $id 的几何无效：${e.message}")
        } catch (e: IllegalArgumentException) {
            fail("形状 $id 的几何无效：${e.message}")
        }
        failIf(outline.isEmpty, "形状 $id 没有可绘制的段")
        outline.subpaths.forEach { sub ->
            failIf(sub.pts.any { !it.isFinite() || kotlin.math.abs(it) > 100.0 }, "形状 $id 的坐标超出 ±100")
        }
        val declaredFill = obj.optionalPaint("fill")
        val declaredStroke = obj.optionalPaint("stroke")
        val strokeWidth = (obj.optionalDouble("stroke_width") ?: 0.04).bounded("$id.stroke_width", 10.0)
        failIf(strokeWidth < 0.0, "形状 $id 的 stroke_width 不能为负")
        val fill = declaredFill ?: if (declaredStroke != null) PidaiPaint.None else PidaiPaint.Ink
        val stroke = declaredStroke ?: PidaiPaint.None
        failIf(fill == PidaiPaint.None && stroke == PidaiPaint.None, "形状 $id 既不填充也不描边")
        return PidaiShape(
            id = id,
            outline = outline,
            imageSrc = null,
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            evenOdd = (obj.optionalString("fill_rule") ?: "nonzero").let {
                failIf(it !in setOf("nonzero", "evenodd"), "形状 $id 的 fill_rule 应为 nonzero 或 evenodd")
                it == "evenodd"
            },
            cap = (obj.optionalString("stroke_cap") ?: "round").also {
                failIf(it !in setOf("butt", "round", "square"), "形状 $id 的 stroke_cap 不受支持")
            },
            join = (obj.optionalString("stroke_join") ?: "round").also {
                failIf(it !in setOf("miter", "round", "bevel"), "形状 $id 的 stroke_join 不受支持")
            },
        )
    }

    private fun flatPoints(obj: JsonObject, id: String): List<Double> {
        val array = obj.array("points")
        failIf(array.size !in 2..2048, "形状 $id 的 points 数量应为 2..2048")
        return array.flatMapIndexed { index: Int, element: JsonElement ->
            val pair = element.asArray("$id.points[$index]")
            failIf(pair.size != 2, "$id.points[$index] 必须是 [x,y]")
            listOf(pair[0].doubleValue.finite("point.x"), pair[1].doubleValue.finite("point.y"))
        }
    }

    // ---- v1：径向皮肤翻译成自由图层 --------------------------------------

    private fun radialLayers(frame: JsonObject, shapes: Map<String, PidaiShape>, label: String): List<PidaiLayer> {
        fun layer(obj: JsonObject, key: String, prefix: String): PidaiLayer {
            val shapeName = obj.string("shape")
            val shape = shapes[shapeName] ?: fail("$label 引用了不存在的形状 $shapeName")
            val sx = (obj.optionalDouble("sx") ?: 1.0).finite("$prefix.sx")
            val sy = (obj.optionalDouble("sy") ?: 1.0).finite("$prefix.sy")
            failIf(sx <= 0.0 || sy <= 0.0 || sx > 10.0 || sy > 10.0, "$label 含无效缩放")
            val alpha = (obj.optionalDouble("alpha") ?: 1.0).finite("$prefix.alpha")
            failIf(alpha !in 0.0..1.0, "$label 含无效透明度")
            return PidaiLayer(
                key = key,
                shape = shape,
                cx = (obj.optionalDouble("cx") ?: 0.0).finite("$prefix.cx"),
                cy = (obj.optionalDouble("cy") ?: 0.0).finite("$prefix.cy"),
                sx = sx,
                sy = sy,
                rot = (obj.optionalDouble("rot_deg") ?: 0.0).finite("$prefix.rot_deg") / 180.0 * PI,
                alpha = alpha,
                radial = true,
            )
        }

        val body = layer(frame, "body", "frame")
        val parts = frame.optionalArray("parts")?.mapIndexed { index, element ->
            val obj = element.asObject("$label.parts[$index]")
            val behind = obj.optionalBoolean("behind") ?: false
            behind to layer(obj, "part$index", "part")
        }.orEmpty()
        failIf(parts.size > 64, "$label 的部件数量应为 0..64")
        return parts.filter { it.first }.map { it.second } + body + parts.filterNot { it.first }.map { it.second }
    }

    private fun parseRadialShape(obj: JsonObject, name: String): DoubleArray {
        val radii = when (obj.string("kind")) {
            "circle" -> DoubleArray(PROFILE_SAMPLES) { obj.double("radius").finite("$name.radius") }
            "outline" -> {
                val points = obj.array("points").mapIndexed { index, point ->
                    val pair = point.asArray("$name.points[$index]")
                    failIf(pair.size != 2, "$name.points[$index] 必须是 [x,y]")
                    Point(pair[0].doubleValue.finite("point.x"), pair[1].doubleValue.finite("point.y"))
                }
                failIf(points.size !in 3..512, "$name.outline 至少需要 3 个点")
                profileFromPolygon(points, 0.0, 0.0)
            }
            "circles" -> {
                val items = obj.array("items").mapIndexed { index, item ->
                    val circle = item.asObject("$name.items[$index]")
                    Disc(circle.double("x"), circle.double("y"), circle.double("r"))
                }
                failIf(items.size !in 1..128, "$name.circles 数量应为 1..128")
                unionOfCirclesProfile(items)
            }
            "radii" -> resample(obj.array("values").map { it.doubleValue.finite("$name.radii") }.toDoubleArray())
            else -> fail("形状 $name 的 kind 不受支持")
        }
        failIf(radii.any { !it.isFinite() || it <= 0.001 || it > 20.0 }, "形状 $name 必须在每个方向都有有限的正半径")
        val maxRadius = obj.optionalDouble("max_radius")
        return if (maxRadius != null) {
            failIf(!maxRadius.isFinite() || maxRadius <= 0.0 || maxRadius > 10.0, "$name.max_radius 无效")
            normalizeRadii(radii, maxRadius)
        } else radii
    }

    // ---- 角色内容 --------------------------------------------------------

    private fun parsePersona(root: JsonObject, motion: PidaiMotion): PidaiPersona {
        val prompt = (root.optionalString("prompt") ?: "").limited("persona.prompt", 4_000)
        val displayName = root.optionalString("display_name")?.limited("persona.display_name", 24)
        val catchphrases = root.optionalArray("catchphrases")?.mapIndexed { index, it ->
            it.stringValue.limited("catchphrases[$index]", 100)
        }.orEmpty()
        failIf(catchphrases.size > 64, "口头禅最多 64 条")
        val mix = (root.optionalDouble("chatter_mix") ?: 0.4).finite("chatter_mix")
        failIf(mix !in 0.0..1.0, "chatter_mix 应在 0..1")
        val ids = mutableSetOf<String>()
        val chatter = root.optionalArray("chatter")?.mapIndexed { index, element ->
            val obj = element.asObject("chatter[$index]")
            val id = obj.string("id").validId("chatter.id")
            failIf(!ids.add(id), "闲话 id 重复：$id")
            val action = obj.optionalString("action")
            failIf(action != null && action !in motion.actions, "闲话 $id 引用了不存在的动作 $action")
            PidaiChatterLine(
                id = id,
                text = obj.string("text").limited("chatter.text", 40),
                hours = obj.optionalRange("hours", 0, 23),
                months = obj.optionalRange("months", 1, 12),
                weekdays = obj.optionalArray("weekdays")?.map {
                    val day = it.intValue
                    failIf(day !in 1..7, "weekdays 应使用 1..7")
                    DayOfWeek.of(day)
                }?.toSet(),
                weight = (obj.optionalDouble("weight") ?: 1.0).finite("chatter.weight"),
                action = action,
            ).also { failIf(it.weight <= 0.0 || it.weight > 1000.0, "闲话权重应为正数") }
        }.orEmpty()
        failIf(chatter.size > 512, "闲话最多 512 条")
        return PidaiPersona(prompt, catchphrases, mix, chatter, displayName)
    }

    private fun verifyHashes(manifest: JsonObject, files: Map<String, ByteArray>) {
        val hashes = manifest.optionalObject("files") ?: return
        hashes.entries.forEach { (name, value) ->
            if (name != "motion.json" && name != "persona.json" && !imagePathRegex.matches(name)) return@forEach
            val bytes = files[name] ?: fail("manifest 声明了缺失文件 $name")
            val expected = value.stringValue.removePrefix("sha256:").lowercase()
            failIf(expected.length != 64, "$name 的 SHA-256 格式无效")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            failIf(actual != expected, "$name 的校验值不匹配")
        }
    }

    /** 载荷字节的稳定短摘要；只取前 8 位十六进制，够区分一次编辑。 */
    private fun contentHash(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (name, bytes) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().take(4).joinToString("") { "%02x".format(it) }
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
        val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val webp = at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        return png || jpeg || webp
    }

    private fun resample(values: DoubleArray): DoubleArray {
        failIf(values.size !in 3..1024, "radii.values 至少需要 3 个值")
        return DoubleArray(PROFILE_SAMPLES) { i ->
            val p = i.toDouble() / PROFILE_SAMPLES * values.size
            val a = p.toInt() % values.size
            val b = (a + 1) % values.size
            values[a] + (values[b] - values[a]) * (p - p.toInt())
        }
    }

    /** `#RGB` / `#RRGGBB` / `#RRGGBBAA`；不接受颜色名，避免各端理解不一致。 */
    private fun parseHexColor(value: String?, field: String): Long? {
        if (value == null) return null
        val text = value.trim()
        failIf(!text.startsWith("#"), "$field 应为 #RGB、#RRGGBB 或 #RRGGBBAA")
        val body = text.substring(1)
        failIf(!Regex("^[0-9A-Fa-f]+$").matches(body), "$field 含非十六进制字符")
        return when (body.length) {
            3 -> {
                val r = body[0].toString().repeat(2)
                val g = body[1].toString().repeat(2)
                val b = body[2].toString().repeat(2)
                0xFF000000L or "$r$g$b".toLong(16)
            }
            6 -> 0xFF000000L or body.toLong(16)
            8 -> {
                val rgb = body.substring(0, 6).toLong(16)
                val alpha = body.substring(6, 8).toLong(16)
                (alpha shl 24) or rgb
            }
            else -> fail("$field 应为 #RGB、#RRGGBB 或 #RRGGBBAA")
        }
    }

    private fun readLimited(input: InputStream, max: Int, label: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            failIf(out.size() + read > max, "$label 超过体积上限")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun objectRoot(bytes: ByteArray, name: String): JsonObject = try {
        AppJson.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("﻿")).jsonObject
    } catch (e: Exception) {
        fail("$name 不是有效的 JSON 对象")
    }

    private fun JsonElement.asObject(label: String): JsonObject =
        (this as? JsonObject) ?: fail("$label 必须是对象")
    private fun JsonElement.asArray(label: String): JsonArray =
        (this as? JsonArray) ?: fail("$label 必须是数组")
    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isPrimitive && it.jsonPrimitive.isString }?.stringValue?.takeIf { it.isNotBlank() }
            ?: fail("缺少文本字段 $name")
    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isNull }?.let { if (it.isPrimitive && it.jsonPrimitive.isString) it.stringValue else fail("$name 必须是文本") }
    private fun JsonObject.double(name: String): Double =
        get(name)?.takeIf { it.isPrimitive && it.jsonPrimitive.isNumber }?.doubleValue ?: fail("缺少数字字段 $name")
    private fun JsonObject.optionalDouble(name: String): Double? =
        get(name)?.takeUnless { it.isNull }?.let { if (it.isPrimitive && it.jsonPrimitive.isNumber) it.doubleValue else fail("$name 必须是数字") }
    private fun JsonObject.int(name: String): Int = get(name)?.intValue ?: fail("缺少整数字段 $name")
    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isNull }?.let { if (it.isPrimitive && it.jsonPrimitive.isBoolean) it.booleanValue else fail("$name 必须是布尔值") }
    private fun JsonObject.array(name: String): JsonArray = get(name)?.asArray(name) ?: fail("缺少数组字段 $name")
    private fun JsonObject.optionalArray(name: String): JsonArray? = get(name)?.takeUnless { it.isNull }?.asArray(name)
    private fun JsonObject.objectValue(name: String): JsonObject = get(name)?.asObject(name) ?: fail("缺少对象字段 $name")
    private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.takeUnless { it.isNull }?.asObject(name)

    /** 颜色槽：`none` 不画，`ink` 跟随主题前景色，`paper` 挖空（不再是底栏背景色），其余按十六进制。 */
    private fun JsonObject.optionalPaint(name: String): PidaiPaint? {
        val raw = optionalString(name) ?: return null
        return when (raw.trim().lowercase()) {
            "none", "transparent" -> PidaiPaint.None
            "ink" -> PidaiPaint.Ink
            "paper" -> PidaiPaint.Paper
            else -> PidaiPaint.Solid(parseHexColor(raw, name)!!)
        }
    }

    private fun JsonObject.optionalRange(name: String, low: Int, high: Int): IntRange? = optionalArray(name)?.let {
        failIf(it.size != 2, "$name 必须是 [start,end]")
        val start = it[0].intValue
        val end = it[1].intValue
        failIf(start !in low..high || end !in low..high || start > end, "$name 范围无效")
        start..end
    }

    private fun String.validId(field: String): String = also { failIf(!idRegex.matches(it), "$field 必须是小写 ASCII id") }
    private fun String.limited(field: String, max: Int): String = trim().also { failIf(it.isEmpty() || it.length > max, "$field 长度应为 1..$max") }
    private fun String.validEase(): String = also { failIf(it !in setOf("linear", "ease-in", "ease-out", "ease-in-out"), "不支持缓动 $it") }
    private fun Double.finite(field: String): Double = also { failIf(!it.isFinite(), "$field 必须是有限数字") }
    private fun Double.bounded(field: String, limit: Double): Double =
        also { failIf(!it.isFinite() || kotlin.math.abs(it) > limit, "$field 超出 ±$limit") }

    private fun fail(message: String): Nothing = throw PidaiSkinFormatException(message)
    private fun failIf(condition: Boolean, message: String) { if (condition) fail(message) }
}
