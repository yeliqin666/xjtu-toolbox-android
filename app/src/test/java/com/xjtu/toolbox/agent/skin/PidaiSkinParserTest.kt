package com.xjtu.toolbox.agent.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PidaiSkinParserTest {
    private val manifest = """
        {"format_version":1,"id":"narcissus","name":"水仙花屁岱","version":"1.0.0","renderer":"radial-motion-v1"}
    """.trimIndent().toByteArray()

    private val motion = """
        {
          "skin_id":"narcissus",
          "color":"#F5C84C",
          "bindings":{"rest":"idle","tap":"bloom"},
          "shapes":{"bud":{"kind":"circle","radius":0.8},"flower":{"kind":"radii","values":[1.0,0.7,1.0,0.7,1.0,0.7,1.0,0.7]}},
          "actions":[
            {"id":"idle","duration":1.0,"loop":true,"frames":[{"t":0,"shape":"bud"},{"t":1,"shape":"bud"}]},
            {"id":"bloom","duration":1.2,"frames":[{"t":0,"shape":"bud"},{"t":1.2,"shape":"flower","ease":"ease-out"}]}
          ]
        }
    """.trimIndent().toByteArray()

    private val persona = """
        {"prompt":"说话清爽","catchphrases":["晒会儿太阳吧"],"chatter_mix":0.35,"chatter":[{"id":"sun","text":"今天的光刚刚好","action":"bloom"}]}
    """.trimIndent().toByteArray()

    @Test
    fun `标准目录可解析动作与角色内容`() {
        val skin = PidaiSkinParser.parseFiles(mapOf("manifest.json" to manifest, "motion.json" to motion, "persona.json" to persona))
        assertEquals("水仙花屁岱", skin.manifest.name)
        assertEquals("bloom", skin.motion.actionFor("tap").id)
        assertEquals(1, skin.manifest.formatVersion)
        assertEquals(64, skin.motion.actions.getValue("bloom").frames.last().layers.single().shape.outline!!.subpaths.single().segments)
        assertEquals("bloom", skin.persona!!.chatter.single().action)
    }

    @Test(expected = PidaiSkinFormatException::class)
    fun `损坏的哈希会被拒绝`() {
        val hashed = """
            {"format_version":1,"id":"narcissus","name":"水仙花屁岱","version":"1","renderer":"radial-motion-v1","files":{"motion.json":"sha256:${"0".repeat(64)}"}}
        """.trimIndent().toByteArray()
        PidaiSkinParser.parseFiles(mapOf("manifest.json" to hashed, "motion.json" to motion))
    }

    @Test
    fun `ZIP 与标准目录使用同一解析结果`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf("manifest.json" to manifest, "motion.json" to motion, "persona.json" to persona).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val skin = PidaiSkinParser.parseZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals("narcissus", skin.manifest.id)
        assertTrue(skin.persona!!.catchphrases.isNotEmpty())
    }

    @Test(expected = PidaiSkinFormatException::class)
    fun `ZIP 路径穿越会被拒绝`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
        PidaiSkinParser.parseZip(ByteArrayInputStream(out.toByteArray()))
    }

    // ---- v2 自由格式 ----------------------------------------------------

    private val freeManifest = """
        {"format_version":2,"id":"chick","name":"鸡蛋小鸡","version":"1.0.0","renderer":"free-motion-v2"}
    """.trimIndent().toByteArray()

    /** 只校验魔数，不解码；渲染层才真正解图。 */
    private val fakePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    )

    private val freeMotion = """
        {
          "skin_id":"chick",
          "shapes":{
            "shell":{"kind":"path","d":"M -1 0 A 1 1 0 0 1 1 0 Z","fill":"#F2E6C8","stroke":"#2A2118","stroke_width":0.05},
            "ring":{"kind":"path","d":"M -1 0 A 1 1 0 1 0 1 0 A 1 1 0 1 0 -1 0 Z M -0.5 0 A 0.5 0.5 0 1 0 0.5 0 A 0.5 0.5 0 1 0 -0.5 0 Z","fill":"ink","fill_rule":"evenodd"},
            "crack":{"kind":"polyline","points":[[-0.6,0.0],[-0.2,-0.3],[0.2,0.1],[0.6,-0.2]],"stroke":"#2A2118","stroke_width":0.04},
            "fluff":{"kind":"circle","r":0.12,"fill":"#FFD75E"},
            "me":{"kind":"image","src":"images/me.png","w":1.6,"h":1.6}
          },
          "actions":[
            {"id":"idle","duration":1.2,"loop":true,"frames":[
              {"t":0,"layers":[{"shape":"shell","key":"body"}]},
              {"t":1.2,"layers":[{"shape":"shell","key":"body","sy":1.04}]}
            ]},
            {"id":"hatch","duration":1.0,"frames":[
              {"t":0,"ease":"ease-out","layers":[
                {"shape":"shell","key":"body"},
                {"shape":"crack","key":"crack","alpha":0},
                {"shape":"fluff","key":"fluff","alpha":0,"cx":0,"cy":0}
              ]},
              {"t":1.0,"layers":[
                {"shape":"ring","key":"body","fill":"#FFD75E"},
                {"shape":"crack","key":"crack","alpha":1},
                {"shape":"fluff","key":"fluff","alpha":1,"cx":0.8,"cy":-0.7,"rot_deg":120,"pivot_x":0.2}
              ]}
            ]},
            {"id":"photo","duration":0.8,"frames":[
              {"t":0,"layers":[{"shape":"me","key":"face","alpha":0}]},
              {"t":0.8,"layers":[{"shape":"me","key":"face","alpha":1}]}
            ]}
          ],
          "bindings":{"rest":"idle","tap":"hatch"}
        }
    """.trimIndent().toByteArray()

    private fun freeFiles() = mapOf(
        "manifest.json" to freeManifest,
        "motion.json" to freeMotion,
        "images/me.png" to fakePng,
    )

    @Test
    fun `自由格式接受任意路径、颜色、层数与位图`() {
        val skin = PidaiSkinParser.parseFiles(freeFiles())
        assertEquals(2, skin.manifest.formatVersion)
        val hatch = skin.motion.actions.getValue("hatch")
        assertEquals(3, hatch.frames.first().layers.size)
        val ring = hatch.frames.last().layers.first().shape
        assertEquals(2, ring.outline!!.subpaths.size)
        assertTrue(ring.evenOdd)
        val crack = hatch.frames.last().layers[1].shape
        assertTrue(!crack.outline!!.subpaths.single().closed)
        assertEquals(PidaiPaint.None, crack.fill)
        assertEquals("images/me.png", skin.motion.actions.getValue("photo").frames.first().layers.single().shape.imageSrc)
        assertEquals(1, skin.images.size)
    }

    @Test
    fun `每层的颜色可以被关键帧覆盖`() {
        val skin = PidaiSkinParser.parseFiles(freeFiles())
        val last = skin.motion.actions.getValue("hatch").frames.last().layers.first()
        assertEquals(PidaiPaint.Solid(0xFFFFD75EL), last.fill)
        assertEquals(PidaiPaint.Ink, last.shape.fill)
    }

    @Test
    fun `位图必须随包提供且是真图`() {
        val missing = runCatching {
            PidaiSkinParser.parseFiles(mapOf("manifest.json" to freeManifest, "motion.json" to freeMotion))
        }.exceptionOrNull()
        assertTrue(missing is PidaiSkinFormatException)
        val bogus = runCatching {
            PidaiSkinParser.parseFiles(freeFiles() + ("images/me.png" to ByteArray(32)))
        }.exceptionOrNull()
        assertTrue(bogus is PidaiSkinFormatException)
    }

    @Test
    fun `motion 引用到的位图清单可以在解析前读出`() {
        assertEquals(listOf("images/me.png"), PidaiSkinParser.assetRefs(freeMotion))
    }

    @Test(expected = PidaiSkinFormatException::class)
    fun `格式版本与渲染器必须相符`() {
        val mixed = """
            {"format_version":2,"id":"chick","name":"鸡蛋小鸡","version":"1.0.0","renderer":"radial-motion-v1"}
        """.trimIndent().toByteArray()
        PidaiSkinParser.parseFiles(freeFiles() + ("manifest.json" to mixed))
    }

    @Test(expected = PidaiSkinFormatException::class)
    fun `同一关键帧里的图层 key 不能重复`() {
        val dup = freeMotion.toString(Charsets.UTF_8)
            .replace("{\"shape\":\"crack\",\"key\":\"crack\",\"alpha\":0}", "{\"shape\":\"crack\",\"key\":\"body\",\"alpha\":0}")
            .toByteArray()
        PidaiSkinParser.parseFiles(freeFiles() + ("motion.json" to dup))
    }

    @Test
    fun `自由格式的 ZIP 会带上位图`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            freeFiles().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val skin = PidaiSkinParser.parseZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals("chick", skin.manifest.id)
        assertEquals(1, skin.images.size)
    }

    @Test
    fun `同 id 同版本但内容不同，缓存键必须不同`() {
        val a = PidaiSkinParser.parseFiles(freeFiles())
        val tweaked = freeMotion.toString(Charsets.UTF_8).replace("\"skin_id\":\"chick\"", "\"skin_id\":\"chick\", \"color\":\"#111111\"").toByteArray()
        val b = PidaiSkinParser.parseFiles(freeFiles() + ("motion.json" to tweaked))
        assertEquals(a.manifest.version, b.manifest.version)
        assertTrue("改了 motion.json 却没改版本号时，cacheKey 也必须变", a.cacheKey != b.cacheKey)
        assertEquals(a.cacheKey, PidaiSkinParser.parseFiles(freeFiles()).cacheKey)
    }

    @Test
    fun `persona 可声明覆盖用的显示名`() {
        val withName = """{"prompt":"说话清爽","display_name":"水仙"}""".toByteArray()
        val skin = PidaiSkinParser.parseFiles(mapOf("manifest.json" to manifest, "motion.json" to motion, "persona.json" to withName))
        assertEquals("水仙", skin.persona!!.displayName)
    }

    @Test
    fun `没有 display_name 时为 null，不是空串`() {
        val skin = PidaiSkinParser.parseFiles(mapOf("manifest.json" to manifest, "motion.json" to motion, "persona.json" to persona))
        assertEquals(null, skin.persona!!.displayName)
    }

    @Test(expected = PidaiSkinFormatException::class)
    fun `display_name 不能是空白`() {
        val blank = """{"display_name":"   "}""".toByteArray()
        PidaiSkinParser.parseFiles(mapOf("manifest.json" to manifest, "motion.json" to motion, "persona.json" to blank))
    }
}
