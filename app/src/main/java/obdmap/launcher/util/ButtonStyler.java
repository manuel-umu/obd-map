package obdmap.launcher.util;

import android.widget.Button;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;

import obdmap.launcher.R;

/** Paleta de botones de la app: acción principal (relleno ámbar) y secundaria (superficie). */
public final class ButtonStyler {

    private ButtonStyler() {
    }

    /** Botón secundario; el texto va ámbar de noche y oscuro de día por contraste al sol. */
    public static void applySecondary(Button button, boolean isNight) {
        button.setBackgroundResource(secondaryBackground(isNight));
        button.setTextColor(ContextCompat.getColor(button.getContext(),
                isNight ? R.color.accent : R.color.text_primary_day));
    }

    /** Botón secundario de solo icono; el tinte sigue el mismo criterio que el texto. */
    public static void applySecondaryIcon(ImageButton button, boolean isNight) {
        button.setBackgroundResource(secondaryBackground(isNight));
        button.setColorFilter(ContextCompat.getColor(button.getContext(),
                isNight ? R.color.accent : R.color.text_primary_day));
    }

    /** Botón de acción principal; no depende del modo día/noche. */
    public static void applyPrimary(Button button) {
        button.setBackgroundResource(R.drawable.btn_hud_primary);
        button.setTextColor(ContextCompat.getColor(button.getContext(), R.color.on_accent));
    }

    /** Fondo de superficie de los botones secundarios. */
    private static int secondaryBackground(boolean isNight) {
        return isNight ? R.drawable.btn_hud_night : R.drawable.btn_hud_day;
    }
}
