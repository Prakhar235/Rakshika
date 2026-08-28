package com.rakshika.app.demo

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Wraps Android's on-device TextToSpeech so the demo playback loop can `speak(text)` and
 * suspend until the utterance finishes — mirroring how the web version chains SpeechSynthesis
 * utterances. Falls back to a timed delay if no TTS engine is available on the device.
 */
class DemoNarrator(context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
            }
        }
    }

    suspend fun speak(text: String) {
        val tts = engine
        if (tts == null || !ready) {
            delay(estimateMs(text))
            return
        }
        suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }
            })
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resume(Unit)
            }
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
    }

    private fun estimateMs(text: String): Long = (text.length * 55L).coerceAtLeast(1400L)
}
