package obdmap.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivitySettingsBinding;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.util.ButtonStyler;
import obdmap.launcher.util.DayNightMode;

/**
 * Pantalla de Ajustes: menú de nivel superior. Solo navega a las distintas
 * secciones (Bluetooth OBD, ajustes del sistema, debug e información). El detalle
 * de cada una vive en su propia Activity.
 */
public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private PrefsManager prefsManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);
        updateDebugOverlayLabel();
        binding.btnToggleDebugOverlay.setOnClickListener(v -> toggleDebugOverlay());
        updatePerfOverlayLabel();
        binding.btnTogglePerfOverlay.setOnClickListener(v -> togglePerfOverlay());
        updatePerfFullLabel();
        binding.btnTogglePerfFull.setOnClickListener(v -> togglePerfFull());
        updateDayNightLabel();
        binding.btnDayNight.setOnClickListener(v -> toggleDayNight());

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnBluetooth.setOnClickListener(
                v -> startActivity(new Intent(this, ObdBluetoothActivity.class)));
        binding.btnSystemSettings.setOnClickListener(v -> openSystemSettings());
        binding.btnObdDebug.setOnClickListener(
                v -> startActivity(new Intent(this, ObdDebugActivity.class)));
        binding.btnInfo.setOnClickListener(
                v -> startActivity(new Intent(this, InfoActivity.class)));

        applyDayNightToUi();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    private void toggleDebugOverlay() {
        prefsManager.setDebugOverlayEnabled(!prefsManager.isDebugOverlayEnabled());
        updateDebugOverlayLabel();
    }

    /** Pone en el botón el estado actual del overlay. */
    private void updateDebugOverlayLabel() {
        binding.btnToggleDebugOverlay.setText(prefsManager.isDebugOverlayEnabled()
                ? R.string.settings_debug_overlay_on
                : R.string.settings_debug_overlay_off);
    }

    private void togglePerfOverlay() {
        prefsManager.setPerfOverlayEnabled(!prefsManager.isPerfOverlayEnabled());
        updatePerfOverlayLabel();
    }
    private void updatePerfOverlayLabel() {
        binding.btnTogglePerfOverlay.setText(prefsManager.isPerfOverlayEnabled()
                ? R.string.settings_perf_overlay_on
                : R.string.settings_perf_overlay_off);
    }

    private void togglePerfFull() {
        prefsManager.setPerfFullEnabled(!prefsManager.isPerfFullEnabled());
        updatePerfFullLabel();
    }

    private void updatePerfFullLabel() {
        binding.btnTogglePerfFull.setText(prefsManager.isPerfFullEnabled()
                ? R.string.settings_perf_full_on
                : R.string.settings_perf_full_off);
    }

    private void toggleDayNight() {
        int next;
        switch (prefsManager.getDayNightPref()) {
            case DayNightMode.PREF_AUTO:
                next = DayNightMode.PREF_DAY;
                break;
            case DayNightMode.PREF_DAY:
                next = DayNightMode.PREF_NIGHT;
                break;
            default:
                next = DayNightMode.PREF_AUTO;
                break;
        }
        prefsManager.setDayNightPref(next);
        updateDayNightLabel();
        applyDayNightToUi();
    }

    /** Repinta fondo y botones según el modo día/noche guardado. */
    private void applyDayNightToUi() {
        boolean isNight = prefsManager.isNightMode();
        binding.settingsRoot.setBackgroundColor(ContextCompat.getColor(this,
                isNight ? R.color.background_dark : R.color.background_day));
        ButtonStyler.applySecondary(binding.btnBluetooth, isNight);
        ButtonStyler.applySecondary(binding.btnSystemSettings, isNight);
        ButtonStyler.applySecondary(binding.btnObdDebug, isNight);
        ButtonStyler.applySecondary(binding.btnInfo, isNight);
        ButtonStyler.applySecondary(binding.btnToggleDebugOverlay, isNight);
        ButtonStyler.applySecondary(binding.btnTogglePerfOverlay, isNight);
        ButtonStyler.applySecondary(binding.btnTogglePerfFull, isNight);
        ButtonStyler.applySecondary(binding.btnBack, isNight);
        ButtonStyler.applySecondary(binding.btnDayNight, isNight);
    }

    /** Pone en el botón la preferencia activa. */
    private void updateDayNightLabel() {
        int label;
        switch (prefsManager.getDayNightPref()) {
            case DayNightMode.PREF_DAY:
                label = R.string.day_night_day;
                break;
            case DayNightMode.PREF_NIGHT:
                label = R.string.day_night_night;
                break;
            default:
                label = R.string.day_night_auto;
                break;
        }
        binding.btnDayNight.setText(label);
    }

    /** Abre los Ajustes del sistema; si no existe la app, avisa por Toast. */
    private void openSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.settings_no_bt_settings_app, Toast.LENGTH_LONG).show();
        }
    }
}
