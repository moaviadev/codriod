package dev.moavia.codriod.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

// =====================================================================================================
// Text rules for voice: pure functions, unit tested.
// =====================================================================================================

object VoiceText {
    /** Added to the system prompt for a spoken message. */
    const val PROMPT_NOTE = "The user is talking to you by voice and will hear your final answer read aloud. " +
        "Answer in short, natural spoken sentences, in the language the user spoke. Do not use tables, headings or long lists " +
        "in the answer, and do not paste code into it: write code into files with your tools and just say what you did."

    data class Language(val tag: String, val label: String)

    /** "auto" uses the phone's language for listening and the reply's script for speaking. */
    val languages = listOf(
        Language("auto", "Auto (phone language)"), Language("en-US", "English (US)"), Language("en-GB", "English (UK)"),
        Language("en-IN", "English (India/Pakistan)"), Language("ur-PK", "اردو Urdu"), Language("hi-IN", "हिन्दी Hindi"),
        Language("pa-IN", "ਪੰਜਾਬੀ Punjabi"), Language("ar-SA", "العربية Arabic"), Language("fa-IR", "فارسی Persian"),
        Language("bn-BD", "বাংলা Bengali"), Language("tr-TR", "Türkçe"), Language("id-ID", "Bahasa Indonesia"),
        Language("es-ES", "Español"), Language("fr-FR", "Français"), Language("de-DE", "Deutsch"), Language("zh-CN", "中文"))

    /** Turns a markdown answer into something pleasant to hear. */
    fun forSpeech(markdown: String, maxChars: Int = 1500): String {
        var codeMentioned = false
        val parts = markdown.split("```")
        val out = StringBuilder()
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                if (!codeMentioned) { out.append(" (The code is in the chat.) "); codeMentioned = true }
                return@forEachIndexed
            }
            part.lines().forEach { raw ->
                var line = raw.trim()
                if (line.startsWith("|") || line.matches(Regex("^[-=*_]{3,}$"))) return@forEach
                line = line.replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
                    .replace(Regex("^>\\s?"), "")
                    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
                    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                    .replace(Regex("https?://\\S+"), "a link")
                    .replace(Regex("`([^`]*)`"), "$1")
                    .replace(Regex("(\\*\\*|__|\\*|~~)"), "")
                    .replace(Regex("(?<=\\s|^)_(\\S[^_]*)_(?=\\s|$|[.,!?])"), "$1")
                if (line.isBlank()) return@forEach
                if (out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
                out.append(line)
                if (line.last() !in ".!?:;،۔؟") out.append('.')
            }
        }
        val text = out.toString().replace(Regex("\\s+"), " ").replace(" .", ".").trim()
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val end = cut.indexOfLast { it in ".!?۔؟" }
        return (if (end > maxChars / 2) cut.take(end + 1) else cut.trimEnd() + "…") + " The rest is in the chat."
    }

    private val urduOnly = setOf('ٹ', 'ڈ', 'ڑ', 'ں', 'ے', 'ھ', 'ہ', 'ی', 'ک', 'گ', 'ۓ', 'ۂ', '۔')

    /** Language of a text from its script, as a BCP-47 tag, or null for Latin/unknown. */
    fun detectLanguage(text: String): String? {
        var arabic = 0; var urdu = 0; var devanagari = 0; var bengali = 0; var gurmukhi = 0; var cjk = 0; var letters = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            when (c) {
                in '؀'..'ۿ', in 'ݐ'..'ݿ', in 'ﭐ'..'﷿', in 'ﹰ'..'﻿' -> { arabic++; if (c in urduOnly) urdu++ }
                in 'ऀ'..'ॿ' -> devanagari++
                in 'ঀ'..'৿' -> bengali++
                in '਀'..'੿' -> gurmukhi++
                in '一'..'鿿' -> cjk++
            }
        }
        if (letters == 0) return null
        val threshold = maxOf(3, letters / 5)
        return when {
            arabic >= threshold -> if (urdu > 0) "ur-PK" else if (text.any { it in "پچژگ" }) "fa-IR" else "ar-SA"
            devanagari >= threshold -> "hi-IN"
            bengali >= threshold -> "bn-BD"
            gurmukhi >= threshold -> "pa-IN"
            cjk >= threshold -> "zh-CN"
            else -> null
        }
    }

    private fun words(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    private val yes = setOf("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "allow", "approve", "approved", "run", "apply", "go",
        "continue", "proceed", "haan", "han", "haa", "ha", "ji", "jee", "theek", "thik", "chalao", "chala", "karo", "kardo", "kar",
        "ہاں", "ہا", "جی", "ٹھیک", "چلاؤ", "کرو", "کردو", "ہاں جی", "हाँ", "हां", "जी", "ठीक", "نعم", "بله")
    private val no = setOf("no", "nope", "nah", "deny", "reject", "cancel", "stop", "dont", "don't", "skip", "nahi", "nahin", "na",
        "mat", "ruko", "نہیں", "نہ", "مت", "رکو", "नहीं", "ना", "मत", "لا", "نه")
    private val always = setOf("always", "hamesha", "ہمیشہ", "हमेशा")

    private val questions = setOf("what", "why", "how", "which", "explain", "kya", "kyun", "kaise", "کیا", "کیوں", "کیسے", "क्या", "क्यों")

    /** A spoken answer to an approval card, or null if it was not a clear yes/no (questions are not answers). */
    fun approvalAnswer(text: String): Decision? {
        val w = words(text)
        if (w.isEmpty() || w.size > 8 || text.trim().endsWith("?") || text.trim().endsWith("؟") || w.any { it in questions }) return null
        if (w.any { it in no }) return Decision.Deny
        if (w.any { it in always }) return Decision.AlwaysAllow
        if (w.any { it in yes }) return Decision.Allow
        return null
    }

    /** "build" / "autopilot" when the user approves a plan by voice, else null (the words are a change request). */
    fun planAnswer(text: String): String? {
        val w = words(text)
        if (w.isEmpty() || w.size > 8) return null
        if (w.any { it in setOf("autopilot", "auto", "آٹوپائلٹ", "آٹو") }) return "autopilot"
        if (w.any { it in setOf("build", "approve", "approved", "start", "implement", "banao", "bana", "شروع", "بناؤ", "بنا") } ||
            approvalAnswer(text) == Decision.Allow) return "build"
        return null
    }

    fun isStop(text: String): Boolean {
        val w = words(text)
        return w.size <= 4 && w.any { it in setOf("stop", "cancel", "ruko", "ruk", "bas", "رکو", "رک", "بس", "روکو", "रुको", "बस") }
    }

    /** Splits text for the phone's speech engine, which limits one utterance to about 4000 characters. */
    fun chunks(text: String, max: Int = 3500): List<String> {
        if (text.length <= max) return listOf(text)
        val result = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val cut = rest.take(max)
            val at = maxOf(cut.lastIndexOfAny(charArrayOf('.', '!', '?', '۔', '\n')), cut.lastIndexOf(' ')).takeIf { it > max / 3 } ?: max - 1
            result += rest.substring(0, at + 1).trim()
            rest = rest.substring(at + 1)
        }
        if (rest.isNotBlank()) result += rest.trim()
        return result
    }

    /** What the voice says when the agent needs approval. */
    fun describeApproval(request: ApprovalRequest): String = when (request) {
        is ApprovalRequest.Edit -> "I want to edit ${spokenPath(request.path)}. Should I apply it? Say yes or no."
        is ApprovalRequest.Delete -> "I want to delete ${spokenPath(request.path)}. Say yes or no."
        is ApprovalRequest.Command -> (if (request.dangerous) "Careful, this command can destroy data. " else "") +
            "Can I run: ${request.command.take(140)}? Say yes, no, or always."
        is ApprovalRequest.Action -> "${request.title}. Should I go ahead? Say yes or no."
    }

    private fun spokenPath(path: String) = path.substringAfterLast('/').ifBlank { path }
}

// =====================================================================================================
// Engines: listening (phone or Whisper) and speaking (phone or OpenAI voices).
// =====================================================================================================

enum class VoicePhase { Off, Listening, Transcribing, Thinking, Speaking, Paused }

private interface EarListener {
    fun partial(text: String)
    fun level(value: Float)
    fun result(text: String)
    fun failed(message: String, nothingHeard: Boolean)
    fun transcribing() {}
}

private interface Ear {
    fun start(language: String?, listener: EarListener)
    /** Stops listening and delivers what was heard. */
    fun finish()
    fun cancel()
}

/** Android's speech recognizer (Google or the phone maker's), with live partial results. */
private class PhoneEar(private val context: Context) : Ear {
    private var recognizer: SpeechRecognizer? = null

    override fun start(language: String?, listener: EarListener) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listener.level(0f) }
            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                val nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                listener.failed(when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the internet (or an offline language pack)."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for Codriod."
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
                    12, 13 -> "This language is not available for speech recognition on this phone. Pick another language or use Whisper."
                    else -> if (nothing) "I didn't catch that." else "Speech recognition error ($error)."
                }, nothing)
            }
            override fun onResults(results: Bundle?) {
                if (delivered) return
                delivered = true
                listener.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(listener::partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        if (!language.isNullOrBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        r.startListening(intent)
    }

    override fun finish() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }; recognizer = null }
}

/**
 * Records with the microphone, detects the end of speech from the loudness and sends the audio to an
 * OpenAI-compatible Whisper endpoint (Groq or OpenAI) with the user's own key. Good for Urdu and mixed languages.
 */
private class WhisperEar(private val context: Context, private val scope: CoroutineScope,
    private val transcribe: suspend (File, String?) -> String) : Ear {
    private var recorder: MediaRecorder? = null
    private var job: Job? = null
    private var file: File? = null
    private var listener: EarListener? = null
    private var finishing = false

    override fun start(language: String?, listener: EarListener) {
        cancel()
        this.listener = listener
        finishing = false
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        file = out
        val r = try {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(48_000)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
        } catch (error: Exception) {
            listener.failed("Could not start the microphone: ${error.message}", false); return
        }
        recorder = r
        val code = language?.substringBefore('-')?.takeIf { it.isNotBlank() }
        job = scope.launch(Dispatchers.Main) {
            var heard = false; var loudTicks = 0; var quietMs = 0L; var elapsed = 0L
            var noise = 400f
            while (isActive && !finishing) {
                delay(100); elapsed += 100
                val amp = runCatching { r.maxAmplitude.toFloat() }.getOrDefault(0f)
                if (!heard) noise = noise * 0.9f + amp * 0.1f
                val threshold = maxOf(1500f, noise * 2.5f)
                listener.level((kotlin.math.sqrt(amp / 32767f)).coerceIn(0f, 1f))
                if (amp > threshold) { loudTicks++; quietMs = 0; if (loudTicks >= 2) heard = true } else { loudTicks = 0; quietMs += 100 }
                if (heard && quietMs >= 1400) break
                if (!heard && elapsed >= 9000) { stopRecorder(); listener.failed("I didn't hear anything.", true); return@launch }
                if (elapsed >= 90_000) break
            }
            if (!isActive) return@launch
            stopRecorder()
            listener.level(0f)
            listener.transcribing()
            try {
                val text = withContext(Dispatchers.IO) { transcribe(out, code) }
                if (text.isBlank()) listener.failed("I didn't catch that.", true) else listener.result(text)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { listener.failed("Whisper: ${error.message?.take(160)}", false) }
            finally { out.delete() }
        }
    }

    private fun stopRecorder() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
    }

    override fun finish() { finishing = true }

    override fun cancel() {
        job?.cancel(); job = null
        stopRecorder()
        file?.delete()
    }
}

private interface Mouth {
    fun speak(text: String, language: String?, rate: Float, done: (String?) -> Unit)
    fun stop()
    fun release()
}

/** Android's text-to-speech. Reports a missing voice so the user can install it. */
private class PhoneMouth(context: Context, private val onMissingVoice: (String) -> Unit) : Mouth {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var queued: (() -> Unit)? = null
    private var callback: ((String?) -> Unit)? = null
    private var lastId = ""
    private val currentIds = HashSet<String>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            queued?.invoke(); queued = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { if (utteranceId == lastId) finish(null) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                main.post { if (utteranceId in currentIds) finish("The phone's voice could not speak.") }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { if (utteranceId in currentIds) finish("The phone's voice could not speak ($errorCode).") }
            }
        })
        runCatching { tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) }
    }

    private fun finish(error: String?) { val cb = callback; callback = null; cb?.invoke(error) }

    override fun speak(text: String, language: String?, rate: Float, done: (String?) -> Unit) {
        val go = {
            if (!ready) done("No text-to-speech engine on this phone. Install Google Speech Services from the Play Store.")
            else {
                stopQuiet()
                callback = done
                val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                val result = tts.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onMissingVoice(locale.displayName)
                    tts.setLanguage(Locale.getDefault())
                }
                tts.setSpeechRate(rate)
                val pieces = VoiceText.chunks(text)
                currentIds.clear()
                pieces.forEachIndexed { i, piece ->
                    val id = "cd-${System.nanoTime()}-$i"
                    currentIds += id
                    if (i == pieces.lastIndex) lastId = id
                    tts.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
                }
            }
        }
        if (ready || failed) go() else queued = go
    }

    private fun stopQuiet() { callback = null; currentIds.clear(); lastId = ""; runCatching { tts.stop() } }
    override fun stop() { val cb = callback; stopQuiet(); cb?.invoke(null) }
    override fun release() { runCatching { tts.stop(); tts.shutdown() } }
}

/** OpenAI voices (natural, multilingual) with the user's OpenAI key. Falls back to the phone voice on errors. */
private class CloudMouth(private val context: Context, private val scope: CoroutineScope,
    private val synth: suspend (String, Float) -> ByteArray) : Mouth {
    private var player: MediaPlayer? = null
    private var job: Job? = null
    private var callback: ((String?) -> Unit)? = null

    override fun speak(text: String, language: String?, rate: Float, done: (String?) -> Unit) {
        stopQuiet()
        callback = done
        job = scope.launch(Dispatchers.Main) {
            try {
                val file = File(context.cacheDir, "speech-${System.currentTimeMillis()}.mp3")
                withContext(Dispatchers.IO) { file.writeBytes(synth(text.take(4000), rate)) }
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                p.setDataSource(file.absolutePath)
                p.setOnCompletionListener { file.delete(); release(); finish(null) }
                p.setOnErrorListener { _, _, _ -> file.delete(); release(); finish("Could not play the voice."); true }
                p.prepare()
                p.start()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { finish("Voice: ${error.message?.take(160)}") }
        }
    }

    private fun finish(error: String?) { val cb = callback; callback = null; cb?.invoke(error) }
    private fun stopQuiet() { callback = null; job?.cancel(); release() }
    override fun stop() { val cb = callback; stopQuiet(); cb?.invoke(null) }
    override fun release() { player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null }
}

// =====================================================================================================
// Voice controller: settings, dictation, read aloud and the hands-free conversation loop.
// =====================================================================================================

class VoiceController internal constructor(private val context: Context, private val scope: CoroutineScope, private val agent: AgentController) {
    private val prefs: SharedPreferences = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    // ---- settings ----------------------------------------------------------------------------------------
    var language by mutableStateOf(prefs.getString("voice_lang", "auto") ?: "auto"); private set
    /** "phone", "groq" or "openai". */
    var listenEngine by mutableStateOf(prefs.getString("voice_stt", "phone") ?: "phone"); private set
    /** "phone" or "openai". */
    var speakEngine by mutableStateOf(prefs.getString("voice_tts", "phone") ?: "phone"); private set
    var cloudVoice by mutableStateOf(prefs.getString("voice_name", "alloy") ?: "alloy"); private set
    var rate by mutableFloatStateOf(prefs.getFloat("voice_rate", 1f)); private set
    var autoListen by mutableStateOf(prefs.getBoolean("voice_auto_listen", true)); private set
    var readReplies by mutableStateOf(prefs.getBoolean("voice_read_replies", false)); private set
    var speakReplies by mutableStateOf(prefs.getBoolean("voice_speak", true)); private set

    fun updateLanguage(v: String) { language = v; prefs.edit().putString("voice_lang", v).apply() }
    fun updateListenEngine(v: String) { listenEngine = v; prefs.edit().putString("voice_stt", v).apply(); ear?.cancel(); ear = null }
    fun updateSpeakEngine(v: String) { speakEngine = v; prefs.edit().putString("voice_tts", v).apply(); mouth?.release(); mouth = null }
    fun updateCloudVoice(v: String) { cloudVoice = v; prefs.edit().putString("voice_name", v).apply() }
    fun updateRate(v: Float) { rate = v.coerceIn(0.5f, 2f); prefs.edit().putFloat("voice_rate", rate).apply() }
    fun updateAutoListen(v: Boolean) { autoListen = v; prefs.edit().putBoolean("voice_auto_listen", v).apply() }
    fun updateReadReplies(v: Boolean) { readReplies = v; prefs.edit().putBoolean("voice_read_replies", v).apply() }
    fun updateSpeakReplies(v: Boolean) { speakReplies = v; prefs.edit().putBoolean("voice_speak", v).apply(); if (!v) stopSpeaking() }

    val cloudVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")

    /** Engines usable now: Whisper needs the user's Groq or OpenAI key. */
    fun listenEngines(): List<Pair<String, String>> = buildList {
        add("phone" to "Phone (Google / built-in)")
        if (hasKey("groq")) add("groq" to "Whisper on Groq (your key)")
        if (hasKey("openai")) add("openai" to "OpenAI transcribe (your key)")
    }

    fun speakEngines(): List<Pair<String, String>> = buildList {
        add("phone" to "Phone voice")
        if (hasKey("openai")) add("openai" to "OpenAI voices (your key)")
    }

    private fun hasKey(provider: String) = agent.key(provider).isNotBlank()

    fun hasMicPermission() = context.checkPermission(Manifest.permission.RECORD_AUDIO, android.os.Process.myPid(), android.os.Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED

    // ---- live state ------------------------------------------------------------------------------------------
    /** True while the voice conversation screen is open. */
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf(VoicePhase.Off); private set
    var partial by mutableStateOf(""); private set
    var level by mutableFloatStateOf(0f); private set
    var heard by mutableStateOf(""); private set
    var reply by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var missingVoice by mutableStateOf<String?>(null); private set
    /** True while the composer's mic is dictating. */
    var dictating by mutableStateOf(false); private set
    /** Text being read aloud from the chat (Read button), so the UI can show Stop. */
    var reading by mutableStateOf<String?>(null); private set

    private var ear: Ear? = null
    private var mouth: Mouth? = null
    private var watcher: Job? = null
    private var misses = 0

    private fun ear(): Ear = ear ?: when (listenEngine) {
        "groq", "openai" -> WhisperEar(context, scope) { file, lang ->
            val spec = Providers.byId(listenEngine)!!
            agent.llm.transcribe(spec, agent.key(spec.id), agent.baseUrl(spec),
                if (listenEngine == "groq") "whisper-large-v3-turbo" else "gpt-4o-mini-transcribe", file, lang,
                prompt = "Codriod, Kotlin, Android, GitHub, commit, terminal.")
        }
        else -> PhoneEar(context)
    }.also { ear = it }

    private fun mouth(): Mouth = mouth ?: when (speakEngine) {
        "openai" -> CloudMouth(context, scope) { text, speed ->
            val spec = Providers.byId("openai")!!
            agent.llm.speech(spec, agent.key("openai"), agent.baseUrl(spec), "gpt-4o-mini-tts", cloudVoice, text, speed)
        }
        else -> PhoneMouth(context) { name -> missingVoice = name }
    }.also { mouth = it }

    private fun listenLanguage(): String? = language.takeIf { it != "auto" }
    private fun speakLanguage(text: String): String? = if (language != "auto") language else VoiceText.detectLanguage(text)

    // ---- read aloud & dictation ----------------------------------------------------------------------------

    /** Reads a chat message aloud (the 🔊 button). */
    fun read(text: String) {
        val spoken = VoiceText.forSpeech(text, 6000)
        if (spoken.isBlank()) return
        reading = text
        mouth().speak(spoken, speakLanguage(spoken), rate) { problem ->
            if (reading == text) reading = null
            if (problem != null) error = problem
        }
    }

    fun stopSpeaking() { reading = null; mouth?.stop() }

    /** Live dictation into the composer: [onText] gets partial and final text. */
    fun dictate(onText: (text: String, final: Boolean) -> Unit) {
        if (dictating) { ear?.finish(); return }
        stopSpeaking()
        error = null
        dictating = true
        ear().start(listenLanguage(), object : EarListener {
            override fun partial(text: String) = onText(text, false)
            override fun level(value: Float) { level = value }
            override fun result(text: String) { dictating = false; level = 0f; onText(text, true) }
            override fun failed(message: String, nothingHeard: Boolean) { dictating = false; level = 0f; if (!nothingHeard) error = message }
        })
    }

    fun cancelDictation() { if (dictating) { ear?.cancel(); dictating = false; level = 0f } }

    // ---- conversation -------------------------------------------------------------------------------------------

    fun startConversation() {
        if (active) return
        active = true
        error = null; heard = ""; reply = ""; misses = 0
        cancelDictation(); stopSpeaking()
        watchAgent()
        when {
            agent.pending != null -> say(VoiceText.describeApproval(agent.pending!!.request))
            agent.running -> phase = VoicePhase.Thinking
            else -> listen()
        }
    }

    fun endConversation() {
        active = false
        watcher?.cancel(); watcher = null
        ear?.cancel()
        mouth?.stop()
        phase = VoicePhase.Off
        partial = ""; level = 0f
    }

    /** The big button: interrupt speech, finish listening, or start listening. */
    fun tap() {
        when (phase) {
            VoicePhase.Speaking -> listen()
            VoicePhase.Listening -> ear?.finish()
            VoicePhase.Thinking -> {}
            VoicePhase.Transcribing -> {}
            VoicePhase.Paused, VoicePhase.Off -> listen()
        }
    }

    fun pause() { phase = VoicePhase.Paused; ear?.cancel(); mouth?.stop(); partial = ""; level = 0f }

    /** Stops the agent's current task from the voice screen; the watcher then says "Stopped". */
    fun stopTask() { agent.stop() }

    private fun listen() {
        if (!active) return
        phase = VoicePhase.Listening
        mouth?.stop()
        error = null
        partial = ""
        ear().start(listenLanguage(), object : EarListener {
            override fun partial(text: String) { if (active) partial = text }
            override fun level(value: Float) { if (active) level = value }
            override fun transcribing() { if (active) phase = VoicePhase.Transcribing }
            override fun result(text: String) { if (active) { level = 0f; heard(text) } }
            override fun failed(message: String, nothingHeard: Boolean) {
                if (!active) return
                level = 0f; partial = ""
                if (nothingHeard && ++misses < 3 && phase == VoicePhase.Listening) { listen(); return }
                if (!nothingHeard) error = message
                phase = VoicePhase.Paused
            }
        })
    }

    private fun heard(text: String) {
        val said = text.trim()
        partial = ""
        if (said.isEmpty()) { listen(); return }
        misses = 0
        heard = said
        val pending = agent.pending
        when {
            pending != null -> {
                val decision = VoiceText.approvalAnswer(said)
                if (decision == null) say("Please say yes or no. " + VoiceText.describeApproval(pending.request))
                else { agent.answer(decision); phase = VoicePhase.Thinking }
            }
            agent.running -> if (VoiceText.isStop(said)) stopTask() else say("I'm still working on it. Say stop to cancel.")
            agent.planAwaitingApproval -> when (VoiceText.planAnswer(said)) {
                "autopilot" -> { reply = ""; agent.approvePlan(AgentMode.Autopilot); phase = VoicePhase.Thinking }
                "build" -> { reply = ""; agent.approvePlan(); phase = VoicePhase.Thinking }
                else -> send(said)
            }
            else -> send(said)
        }
    }

    private fun send(text: String) {
        reply = ""
        agent.send(text, voice = true)
        phase = if (agent.running) VoicePhase.Thinking else VoicePhase.Paused
        if (!agent.running) agent.messages.lastOrNull()?.takeIf { it.role == Role.Error }?.let { say(it.text) }
    }

    /** Speaks and then listens again (when hands-free is on). */
    private fun say(text: String, thenListen: Boolean = true) {
        if (!active) return
        ear?.cancel()
        val spoken = VoiceText.forSpeech(text)
        if (!speakReplies || spoken.isBlank()) { if (thenListen && autoListen) listen() else phase = VoicePhase.Paused; return }
        phase = VoicePhase.Speaking
        mouth().speak(spoken, speakLanguage(spoken), rate) { problem ->
            if (!active || phase != VoicePhase.Speaking) return@speak
            if (problem != null) error = problem
            if (thenListen && autoListen) listen() else phase = VoicePhase.Paused
        }
    }

    private fun watchAgent() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.Main) {
            var wasRunning = agent.running
            var announced: PendingApproval? = null
            snapshotFlow { Triple(agent.running, agent.pending, agent.planAwaitingApproval) }.collect { (running, pending, planWaiting) ->
                if (!active) return@collect
                if (pending != null && pending !== announced) {
                    announced = pending
                    say(VoiceText.describeApproval(pending.request))
                }
                if (wasRunning && !running) {
                    val list = agent.messages.toList()
                    val turn = list.drop(list.indexOfLast { it.role == Role.User } + 1)
                    val answer = turn.lastOrNull { it.role == Role.Assistant || it.role == Role.Error }?.text
                        ?: turn.lastOrNull { it.role == Role.Tool }?.summary?.let { "Done. $it" } ?: "Done."
                    reply = answer
                    say(answer + if (planWaiting) "\n\nSay build to start, autopilot to let me run on my own, or tell me what to change." else "")
                }
                wasRunning = running
            }
        }
    }

    fun release() { endConversation(); cancelDictation(); mouth?.release(); mouth = null }
}
