package obdmap.launcher.util;

import androidx.annotation.DrawableRes;

import obdmap.launcher.R;

/**
 * Mapa estático entre el código de signo de maniobra de GraphHopper y el drawable correspondiente.
 */
public final class ManeuverIcons {

    private ManeuverIcons() {}

    /** Devuelve el id de drawable correspondiente al sign de maniobra. */
    @DrawableRes
    public static int drawableForSign(int sign) {
        switch (sign) {
            case -3: return R.drawable.ic_maneuver_turn_sharp_left;
            case -2: return R.drawable.ic_maneuver_turn_left;
            case -1: return R.drawable.ic_maneuver_turn_slight_left;
            case  1: return R.drawable.ic_maneuver_turn_slight_right;
            case  2: return R.drawable.ic_maneuver_turn_right;
            case  3: return R.drawable.ic_maneuver_turn_sharp_right;
            case  4: return R.drawable.ic_maneuver_finish;
            case  5: return R.drawable.ic_maneuver_finish; // VIA_REACHED: igual que destino
            case  6: return R.drawable.ic_maneuver_roundabout;
            case -6: return R.drawable.ic_maneuver_turn_left; // LEAVE_ROUNDABOUT ≈ giro izq.
            case -7: return R.drawable.ic_maneuver_keep_left;
            case  7: return R.drawable.ic_maneuver_keep_right;
            case -98:  // U_TURN_UNKNOWN
            case  -8:  // U_TURN_LEFT
            case   8:  // U_TURN_RIGHT
                return R.drawable.ic_maneuver_uturn;
            case  0:   // CONTINUE_ON_STREET
            default:
                return R.drawable.ic_maneuver_straight;
        }
    }
}
