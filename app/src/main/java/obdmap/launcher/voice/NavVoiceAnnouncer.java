package obdmap.launcher.voice;

import android.content.Context;
import android.text.TextUtils;

import obdmap.launcher.R;
import obdmap.launcher.util.ManeuverVoice;

/**
 * Avisos por voz al acercarse a cada maniobra de la ruta activa
 * Anuncia una sola vez por maniobra al cruzar cada umbral de distancia.
 */
public final class NavVoiceAnnouncer {

    /** Umbrales de aviso en metros, de mayor a menor. */
    private static final int[] THRESHOLDS_M = {500, 200, 50};
    private final Context context;
    private final TtsManager ttsManager;

    // Índice de la maniobra para la que se está anunciando actualmente
    private int announcedForIndex = Integer.MIN_VALUE;

    // announced[i] = true si ya se anunció THRESHOLDS_M[i] para la maniobra actual.
    private final boolean[] announced = new boolean[THRESHOLDS_M.length];

    public NavVoiceAnnouncer(Context context, TtsManager ttsManager) {
        this.context = context.getApplicationContext();
        this.ttsManager = ttsManager;
    }

    /**
     * Procesa un fix de navegación y anuncia por voz si se ha cruzado algún umbral nuevo.
     *
     * @param maneuverIndex     índice de la instrucción actual
     * @param sign              sign de la próxima maniobra
     * @param street            nombre de calle de la próxima maniobra (puede ser vacío)
     * @param distanceToManeuverM distancia en metros hasta la próxima maniobra
     */
    public void onNavUpdate(int maneuverIndex, int sign, String street, double distanceToManeuverM) {
        if (maneuverIndex < 0) {
            // Sin maniobra activa
            reset();
            return;
        }

        if (maneuverIndex != announcedForIndex) {
            // Nueva maniobra
            announcedForIndex = maneuverIndex;
            for (int i = 0; i < announced.length; i++) {
                announced[i] = false;
            }
        }

        // Recorremos de mayor a menor umbral
        for (int i = 0; i < THRESHOLDS_M.length; i++) {
            if (announced[i]) {
                continue;
            }
            if (distanceToManeuverM <= THRESHOLDS_M[i]) {
                for (int j = 0; j <= i; j++) {
                    announced[j] = true;
                }
                announce(THRESHOLDS_M[i], sign, street);
                return;
            }
        }
    }

    /** Resetea el estado de avisos */
    public void reset() {
        announcedForIndex = Integer.MIN_VALUE;
        for (int i = 0; i < announced.length; i++) {
            announced[i] = false;
        }
    }

    /** Construye y encola el texto del aviso para el umbral cruzado */
    private void announce(int thresholdM, int sign, String street) {
        String maneuverText = context.getString(ManeuverVoice.textForSign(sign));
        boolean hasStreet = !TextUtils.isEmpty(street);

        String text;
        if (ManeuverVoice.isFinish(sign)) {
            text = maneuverText;
        } else if (thresholdM <= 50) {
            // Aviso inminente: sin "en X metros"
            text = hasStreet
                    ? context.getString(R.string.voice_now,
                            maneuverText + " " + context.getString(R.string.voice_on_street, street))
                    : context.getString(R.string.voice_now, maneuverText);
        } else {
            text = hasStreet
                    ? context.getString(R.string.voice_in_meters, thresholdM,
                            maneuverText + " " + context.getString(R.string.voice_on_street, street))
                    : context.getString(R.string.voice_in_meters, thresholdM, maneuverText);
        }

        ttsManager.speak(text);
    }
}
