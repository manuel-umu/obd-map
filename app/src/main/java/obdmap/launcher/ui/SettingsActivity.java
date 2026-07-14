package obdmap.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivitySettingsBinding;

/**
 * Pantalla de Ajustes: menú de nivel superior. Solo navega a las distintas
 * secciones (Bluetooth OBD, ajustes del sistema, debug e información). El detalle
 * de cada una vive en su propia Activity.
 */
public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnBluetooth.setOnClickListener(
                v -> startActivity(new Intent(this, ObdBluetoothActivity.class)));
        binding.btnSystemSettings.setOnClickListener(v -> openSystemSettings());
        binding.btnObdDebug.setOnClickListener(
                v -> startActivity(new Intent(this, ObdDebugActivity.class)));
        binding.btnInfo.setOnClickListener(
                v -> startActivity(new Intent(this, InfoActivity.class)));
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
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
