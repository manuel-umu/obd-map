package obdmap.launcher.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import java.util.Locale;

/**
 * Indicaciones de voz del navegador.
 */
public final class TtsManager {

    // Ventana de deduplicación: si se pide hablar el mismo texto dos veces dentro de
    // este margen, se descarta la segunda petición
    private static final long DEDUP_WINDOW_MS = 8000L;
    private static final String UTTERANCE_ID_PREFIX = "obdmap_tts_";
    private final TextToSpeech textToSpeech;
    private boolean ready = false;
    private String lastSpokenText = null;
    private long lastSpokenTimeMs = 0L;
    private int utteranceCounter = 0;

    public TtsManager(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                handleInit(status);
            }
        });
    }

    private void handleInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false;
            return;
        }

        int result = textToSpeech.setLanguage(new Locale("es", "ES"));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Algunas radios solo traen el paquete de voz español genérico sin el dialecto es-ES
            result = textToSpeech.setLanguage(new Locale("es"));
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            ready = false;
            return;
        }

        ready = true;
        textToSpeech.setSpeechRate(1.0f);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        textToSpeech.setAudioAttributes(audioAttributes);

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
            }

            @Override
            public void onError(String utteranceId) {
            }
        });
    }

    /**
     * Encola un mensaje de voz sin interrumpir el que esté sonando (QUEUE_ADD).
     */
    public void speak(String text) {
        if (!ready || TextUtils.isEmpty(text)) {
            return;
        }
        if (isDuplicate(text)) {
            return;
        }
        String utteranceId = UTTERANCE_ID_PREFIX + (utteranceCounter++);
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
        registerSpoken(text);
    }

    /**
     * Igual que {@link #speak(String)} pero descarta la cola actual (QUEUE_FLUSH).
     * Pensado para avisos urgentes
     */
    public void speakNow(String text) {
        if (!ready || TextUtils.isEmpty(text)) {
            return;
        }
        if (isDuplicate(text)) {
            return;
        }
        String utteranceId = UTTERANCE_ID_PREFIX + (utteranceCounter++);
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        registerSpoken(text);
    }

    private boolean isDuplicate(String text) {
        long now = SystemClock.elapsedRealtime();
        return text.equals(lastSpokenText) && (now - lastSpokenTimeMs) < DEDUP_WINDOW_MS;
    }

    private void registerSpoken(String text) {
        lastSpokenText = text;
        lastSpokenTimeMs = SystemClock.elapsedRealtime();
    }

    /** Detiene la reproducción en curso sin liberar el motor. */
    public void stop() {
        if (ready) {
            textToSpeech.stop();
        }
    }

    /** Detiene y libera los recursos del motor */
    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        ready = false;
    }
}
