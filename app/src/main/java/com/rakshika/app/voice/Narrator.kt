package com.rakshika.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks out the app's key decisions as they happen — route recommendation, ride start,
 * reroute, SOS, arrival — so the ride can be followed without staring at the screen.
 * Best-effort: if the device has no TTS engine, calls to [say] are silently dropped.
 */
class Narrator(context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.getDefault()
            } else {
                Log.w(TAG, "TextToSpeech init failed (status=$status) — narration disabled")
            }
        }
    }

    /** Queued after whatever's already speaking, so decisions narrate in the order they happened. */
    fun say(text: String) {
        if (!ready) return
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    /** Cuts off whatever's currently queued — used when the user backs out of a ride. */
    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private companion object { const val TAG = "Narrator" }
}
