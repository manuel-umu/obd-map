package obdmap.launcher.util;

import androidx.annotation.StringRes;

import obdmap.launcher.R;

/**
 * Mapa estático entre el código de signo de maniobra de GraphHopper y el string
 * en español a hablar por TTS
 */
public final class ManeuverVoice {

    private ManeuverVoice() {}

    /** Devuelve el id de string con el texto hablado de la maniobra. */
    @StringRes
    public static int textForSign(int sign) {
        switch (sign) {
            case -3: return R.string.maneuver_turn_sharp_left;
            case -2: return R.string.maneuver_turn_left;
            case -1: return R.string.maneuver_turn_slight_left;
            case  1: return R.string.maneuver_turn_slight_right;
            case  2: return R.string.maneuver_turn_right;
            case  3: return R.string.maneuver_turn_sharp_right;
            case  4: return R.string.maneuver_finish;
            case  5: return R.string.maneuver_finish; // VIA_REACHED: igual que destino
            case  6: return R.string.maneuver_roundabout;
            case -6: return R.string.maneuver_turn_left; // LEAVE_ROUNDABOUT ≈ giro izq.
            case -7: return R.string.maneuver_keep_left;
            case  7: return R.string.maneuver_keep_right;
            case -98:  // U_TURN_UNKNOWN
            case  -8:  // U_TURN_LEFT
            case   8:  // U_TURN_RIGHT
                return R.string.maneuver_uturn;
            case  0:   // CONTINUE_ON_STREET
                return R.string.maneuver_continue;
            default:
                return R.string.maneuver_generic;
        }
    }

    /**
     * @param sign sign de maniobra de GraphHopper
     * @return true si el sign representa la llegada al destino (no debe llevar prefijo de distancia)
     */
    public static boolean isFinish(int sign) {
        return sign == 4 || sign == 5;
    }
}
