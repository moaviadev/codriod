package dev.moavia.codriod.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceAndVisionTest {

    @Test fun speechTextDropsMarkdownAndCode() {
        val spoken = VoiceText.forSpeech("""
            ## Done
            I updated **MainActivity.kt** and `build.gradle`:
            - added a [button](https://x.dev/a)
            - fixed the crash
            ```kotlin
            val x = 1
            ```
            | a | b |
            |---|---|
            See https://example.com for more
        """.trimIndent())
        assertEquals("Done. I updated MainActivity.kt and build.gradle: added a button. fixed the crash. (The code is in the chat.) See a link for more.", spoken)
        assertFalse(spoken.contains("*") || spoken.contains("`") || spoken.contains("|"))
    }

    @Test fun longSpeechIsCutAtASentence() {
        val text = (1..200).joinToString(" ") { "Sentence number $it is here." }
        val spoken = VoiceText.forSpeech(text, 300)
        assertTrue(spoken.length < 360)
        assertTrue(spoken.endsWith("The rest is in the chat."))
        assertTrue(spoken.substringBefore(" The rest").endsWith("."))
    }

    @Test fun detectsScripts() {
        assertEquals("ur-PK", VoiceText.detectLanguage("میں نے فائل ٹھیک کر دی ہے"))
        assertEquals("ar-SA", VoiceText.detectLanguage("لقد قمت بتحديث الملف"))
        assertEquals("hi-IN", VoiceText.detectLanguage("मैंने फ़ाइल ठीक कर दी है"))
        assertNull(VoiceText.detectLanguage("I fixed the file"))
        assertNull(VoiceText.detectLanguage("```\n123\n```"))
    }

    @Test fun approvalsByVoice() {
        assertEquals(Decision.Allow, VoiceText.approvalAnswer("Yes, run it"))
        assertEquals(Decision.Allow, VoiceText.approvalAnswer("haan chalao"))
        assertEquals(Decision.Allow, VoiceText.approvalAnswer("جی ہاں"))
        assertEquals(Decision.Deny, VoiceText.approvalAnswer("no don't"))
        assertEquals(Decision.Deny, VoiceText.approvalAnswer("نہیں"))
        assertEquals(Decision.Deny, VoiceText.approvalAnswer("nahi yaar"))
        assertEquals(Decision.AlwaysAllow, VoiceText.approvalAnswer("always allow"))
        assertNull(VoiceText.approvalAnswer("what does this command do exactly"))
        assertNull(VoiceText.approvalAnswer("please create a new login screen with a button and a text field for the user"))
        assertEquals("build", VoiceText.planAnswer("ok build it"))
        assertEquals("autopilot", VoiceText.planAnswer("run on autopilot"))
        assertNull(VoiceText.planAnswer("add a dark mode step too"))
        assertTrue(VoiceText.isStop("stop"))
        assertTrue(VoiceText.isStop("رکو"))
        assertFalse(VoiceText.isStop("add a stop button to the timer screen please"))
    }

    @Test fun chunksLongText() {
        val text = "Hello world. ".repeat(800)
        val parts = VoiceText.chunks(text, 3500)
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.length <= 3500 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test fun visionFromModelLists() {
        val list = """{"data":[
          {"id":"openai/gpt-4o","architecture":{"input_modalities":["text","image"]}},
          {"id":"deepseek/deepseek-chat","architecture":{"input_modalities":["text"]}},
          {"id":"meta/old","architecture":{"modality":"text+image->text"}},
          {"id":"mistral-small","capabilities":{"vision":true,"completion_chat":true}},
          {"id":"gh/model","supported_input_modalities":["text","image","audio"]},
          {"id":"plain-name-model"}
        ]}"""
        val models = ModelListParser.parse(list).associateBy { it.id }
        assertEquals(true, models["openai/gpt-4o"]!!.vision)
        assertEquals(false, models["deepseek/deepseek-chat"]!!.vision)
        assertEquals(true, models["meta/old"]!!.vision)
        assertEquals(true, models["mistral-small"]!!.vision)
        assertEquals(true, models["gh/model"]!!.vision)
        assertNull(models["plain-name-model"]!!.vision)
        assertNull(ModelCaps.visionFromMetadata(JSONObject("""{"id":"x"}""")))
    }

    @Test fun visionGuessFromNames() {
        listOf("gpt-4o-mini", "gpt-5", "claude-sonnet-4-5", "gemini-2.5-flash", "llama-4-scout-17b", "qwen2.5-vl-72b-instruct",
            "meta-llama/llama-3.2-11b-vision-instruct", "pixtral-12b", "grok-4", "gemma-3-27b-it", "internvl3-78b")
            .forEach { assertTrue(it, ModelCaps.vision(it)) }
        listOf("o3-mini", "gpt-oss-120b", "deepseek-r1", "llama-3.3-70b-versatile", "qwen3-coder", "mistral-large-latest", "o1-mini")
            .forEach { assertFalse(it, ModelCaps.vision(it)) }
    }

    @Test fun multipartBody() {
        val form = Multipart("BOUND")
        form.field("model", "whisper-large-v3-turbo")
        form.file("file", "a.m4a", "audio/mp4", byteArrayOf(1, 2, 3))
        val body = String(form.finish(), Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"a.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n\u0001\u0002\u0003\r\n"))
        assertTrue(body.endsWith("--BOUND--\r\n"))
    }
}
