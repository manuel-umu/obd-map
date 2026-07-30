package obdmap.launcher.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import obdmap.launcher.R;

/**
 * Aplicador de modo día/noche que recorre un árbol de vistas y colorea cada una
 * según el rol declarado en su {@code android:tag}.
 */
public final class ThemeApplier {

    /** Fondo de tarjeta o panel. */
    public static final String ROLE_SURFACE = "surface";
    /** Texto secundario (etiquetas y unidades). */
    public static final String ROLE_LABEL = "label";
    /** Valor destacado; ámbar de noche y oscuro de día. */
    public static final String ROLE_VALUE = "value";
    /** Botón de acción principal. */
    public static final String ROLE_PRIMARY = "primary";
    /** Vista con colores de significado propio: ni ella ni sus hijos se tocan. */
    public static final String ROLE_SKIP = "skip";

    private ThemeApplier() {
    }

    /** Repinta el árbol desde root; el propio root recibe el fondo general. */
    public static void apply(View root, boolean isNight) {
        root.setBackgroundColor(color(root, isNight
                ? R.color.background_dark : R.color.background_day));
        applyToChildren(root, isNight);
    }

    private static void applyToChildren(View parent, boolean isNight) {
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyToView(group.getChildAt(i), isNight);
        }
    }

    private static void applyToView(View view, boolean isNight) {
        Object tag = view.getTag();
        String roles = (tag instanceof String) ? (String) tag : null;
        if (hasRole(roles, ROLE_SKIP)) {
            return;
        }

        if (hasRole(roles, ROLE_SURFACE)) {
            view.setBackgroundColor(color(view, isNight
                    ? R.color.surface_dark : R.color.surface_day));
        }

        if (view instanceof Button) {
            Button button = (Button) view;
            if (hasRole(roles, ROLE_PRIMARY)) {
                ButtonStyler.applyPrimary(button);
            } else {
                ButtonStyler.applySecondary(button, isNight);
            }
            return;
        }
        if (view instanceof ImageButton) {
            ButtonStyler.applySecondaryIcon((ImageButton) view, isNight);
            return;
        }
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color(view, textColorFor(roles, isNight)));
            return;
        }
        applyToChildren(view, isNight);
    }

    private static int textColorFor(@Nullable String roles, boolean isNight) {
        if (hasRole(roles, ROLE_LABEL)) {
            return isNight ? R.color.text_secondary : R.color.text_secondary_day;
        }
        if (hasRole(roles, ROLE_VALUE)) {
            return isNight ? R.color.accent : R.color.text_primary_day;
        }
        return isNight ? R.color.text_primary : R.color.text_primary_day;
    }

    // Auxiliares

    private static boolean hasRole(@Nullable String roles, String role) {
        return roles != null && roles.contains(role);
    }

    private static int color(View view, int colorRes) {
        return ContextCompat.getColor(view.getContext(), colorRes);
    }
}
