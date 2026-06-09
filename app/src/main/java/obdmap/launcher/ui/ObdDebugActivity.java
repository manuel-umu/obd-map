package obdmap.launcher.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivityObdDebugBinding;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.service.ObdService;
import obdmap.launcher.service.ObdServiceListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pantalla de debug del Bloque C. Muestra los valores OBD2 en bruto (sin
 * conversión a unidades reales) y el estado de la conexión.
 *
 * <p>Los valores convertidos a RPM reales, km/h, etc., llegan en la Fase 3.
 * Aquí se muestra el rawValue exacto que entrega el reader para facilitar
 * la validación del protocolo sin ruido de formateo.</p>
 */
public final class ObdDebugActivity extends AppCompatActivity implements ObdServiceListener {

    // El formateo de hora no está en un bucle caliente (solo se actualiza en cada
    // dato recibido), así que un SimpleDateFormat es aceptable aquí.
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private ActivityObdDebugBinding binding;
    private PrefsManager prefsManager;

    @Nullable private ObdService boundService;
    private boolean serviceBound = false;

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityObdDebugBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);

        binding.debugBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Si hay MAC configurada, aseguramos que el servicio esté arrancado
        // antes de intentar el bind (el servicio puede ya estar vivo desde MainActivity).
        String mac = prefsManager.getObdMac();
        if (mac != null && !mac.isEmpty()) {
            Intent intent = new Intent(this, ObdService.class);
            ContextCompat.startForegroundService(this, intent);
        }

        Intent bindIntent = new Intent(this, ObdService.class);
        bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) {
            if (boundService != null) {
                boundService.setServiceListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
            boundService = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    // =========================================================================
    // ServiceConnection
    // =========================================================================

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ObdService.LocalBinder localBinder = (ObdService.LocalBinder) binder;
            boundService = localBinder.getService();
            serviceBound = true;
            boundService.setServiceListener(ObdDebugActivity.this);

            // Pintamos el estado actual inmediatamente para no esperar al siguiente callback.
            refreshAllFields();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // El proceso del servicio murió de forma inesperada: poco probable en
            // un ForegroundService, pero lo manejamos limpiamente.
            boundService = null;
            serviceBound = false;
        }
    };

    // =========================================================================
    // ObdServiceListener — main thread garantizado por ObdService
    // =========================================================================

    @Override
    public void onObdStateChanged(@ObdState.State int state) {
        updateStateLabel(state);
    }

    @Override
    public void onObdDataUpdated(@NonNull String pid, int rawValue) {
        // Actualizamos el campo correspondiente y el timestamp.
        switch (pid) {
            case "010C":
                binding.debugRpmValue.setText(String.valueOf(rawValue));
                break;
            case "010D":
                binding.debugSpeedValue.setText(String.valueOf(rawValue));
                break;
            case "0104":
                binding.debugLoadValue.setText(String.valueOf(rawValue));
                break;
            default:
                break;
        }
        binding.debugLastReading.setText(
                getString(R.string.debug_last_reading, TIME_FORMAT.format(new Date())));
    }

    // =========================================================================
    // Helpers de UI
    // =========================================================================

    /**
     * Rellena todos los campos con el estado actual del servicio justo después
     * de que el bind se complete.
     */
    private void refreshAllFields() {
        if (boundService == null) {
            return;
        }

        updateStateLabel(boundService.getObdState());

        int rpm   = boundService.getLastRpm();
        int speed = boundService.getLastSpeed();
        int load  = boundService.getLastLoad();

        binding.debugRpmValue.setText(rpm   >= 0 ? String.valueOf(rpm)   : getString(R.string.debug_no_data));
        binding.debugSpeedValue.setText(speed >= 0 ? String.valueOf(speed) : getString(R.string.debug_no_data));
        binding.debugLoadValue.setText(load  >= 0 ? String.valueOf(load)  : getString(R.string.debug_no_data));

        long ts = boundService.getLastReadingTimestampMs();
        if (ts > 0) {
            binding.debugLastReading.setText(
                    getString(R.string.debug_last_reading, TIME_FORMAT.format(new Date(ts))));
        } else {
            binding.debugLastReading.setText(getString(R.string.debug_no_reading_yet));
        }
    }

    private void updateStateLabel(@ObdState.State int state) {
        if (binding == null) {
            return;
        }
        String text;
        switch (state) {
            case ObdState.CONNECTING:
                text = getString(R.string.obd_state_connecting);
                break;
            case ObdState.INITIALIZING:
                text = getString(R.string.obd_state_initializing);
                break;
            case ObdState.READY:
                text = getString(R.string.obd_state_connected);
                break;
            case ObdState.RECONNECTING:
                text = getString(R.string.obd_state_reconnecting);
                break;
            case ObdState.FAILED:
                String err = (boundService != null) ? boundService.getLastErrorDescription() : null;
                text = err != null
                        ? getString(R.string.obd_state_failed, err)
                        : getString(R.string.obd_state_failed_short);
                break;
            default:
                text = getString(R.string.obd_state_connecting);
                break;
        }
        binding.debugStateValue.setText(text);
    }
}
