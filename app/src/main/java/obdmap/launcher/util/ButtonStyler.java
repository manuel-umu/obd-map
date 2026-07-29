package obdmap.launcher.util;

import android.widget.Button;

import androidx.core.content.ContextCompat;

import obdmap.launcher.R;

/** Paleta de botones de la app: acción principal (relleno ámbar) y secundaria (superficie). */
public final class ButtonStyler {

    private ButtonStyler() {
    }

    /** Botón secundario; el texto va ámbar de noche y oscuro de día por contraste al sol. */
    public static void applySecondary(Button button, boolean isNight) {
        button.setBackgroundResource(isNight ? R.drawable.btn_hud_night : R.drawable.btn_hud_day);
        button.setTextColor(ContextCompat.getColor(button.getContext(),
                isNight ? R.color.accent : R.color.text_primary_day));
    }

    /** Botón de acción principal; no depende del modo día/noche. */
    public static void applyPrimary(Button button) {
        button.setBackgroundResource(R.drawable.btn_hud_primary);
        button.setTextColor(ContextCompat.getColor(button.getContext(), R.color.on_accent));
    }
}
