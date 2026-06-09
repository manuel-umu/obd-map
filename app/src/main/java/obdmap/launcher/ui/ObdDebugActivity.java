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
import obdmap.launcher.obd.FuelCalculator;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.service.ObdService;
import obdmap.launcher.service.ObdServiceListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pantalla de debug del bloque OBD2.
 * Muestra todos los valores de telemetría incluyendo los de consumo diésel (Fase 3).
 */
public final class ObdDebugActivity extends AppCompatActivity implements ObdServiceListener {

    // El formateo de hora no está en un bucle caliente, así que SimpleDateFormat es aceptable.
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

        binding.debugResetAvgButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (boundService != null) {
                    boundService.resetAverageFuel();
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

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
            refreshAllFields();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
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
        if (binding == null) {
            return;
        }

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
            case "0111":
                binding.debugThrottleValue.setText(String.valueOf(rawValue));
                break;
            case "0105":
                // Puede ser negativo; valueOf lo formatea correctamente.
                binding.debugCoolantValue.setText(String.valueOf(rawValue));
                break;
            case "010F":
                binding.debugIatValue.setText(String.valueOf(rawValue));
                break;
            case "010B":
                binding.debugMapValue.setText(String.valueOf(rawValue));
                break;
            case "015E":
                // rawValue es punto fijo; L/h real = raw / 20.
                updateFuelRateField(rawValue);
                break;
            default:
                break;
        }

        binding.debugLastReading.setText(
                getString(R.string.debug_last_reading, TIME_FORMAT.format(new Date())));

        // Actualizamos los campos de consumo en cada dato recibido.
        updateFuelFields();
    }

    // =========================================================================
    // Helpers de UI
    // =========================================================================

    /**
     * Rellena todos los campos con el estado actual del servicio justo tras el bind.
     */
    private void refreshAllFields() {
        if (boundService == null || binding == null) {
            return;
        }

        updateStateLabel(boundService.getObdState());

        int rpm   = boundService.getLastRpm();
        int speed = boundService.getLastSpeed();
        int load  = boundService.getLastLoad();
        int throttle = boundService.getLastThrottle();
        int coolant  = boundService.getLastCoolant();
        int iat      = boundService.getLastIat();
        int mapKpa   = boundService.getLastMapKpa();

        String noData = getString(R.string.debug_no_data);

        binding.debugRpmValue.setText(rpm   >= 0 ? String.valueOf(rpm)   : noData);
        binding.debugSpeedValue.setText(speed >= 0 ? String.valueOf(speed) : noData);
        binding.debugLoadValue.setText(load  >= 0 ? String.valueOf(load)  : noData);
        binding.debugThrottleValue.setText(throttle >= 0 ? String.valueOf(throttle) : noData);

        // Coolant e IAT tienen sentinel Integer.MIN_VALUE (pueden ser negativos).
        binding.debugCoolantValue.setText(
                coolant != Integer.MIN_VALUE ? String.valueOf(coolant) : noData);
        binding.debugIatValue.setText(
                iat != Integer.MIN_VALUE ? String.valueOf(iat) : noData);
        binding.debugMapValue.setText(mapKpa >= 0 ? String.valueOf(mapKpa) : noData);

        updateFuelRateField(boundService.getLastFuelRateRaw());
        updateFuelFields();

        long ts = boundService.getLastReadingTimestampMs();
        if (ts > 0) {
            binding.debugLastReading.setText(
                    getString(R.string.debug_last_reading, TIME_FORMAT.format(new Date(ts))));
        } else {
            binding.debugLastReading.setText(getString(R.string.debug_no_reading_yet));
        }
    }

    /**
     * Actualiza el campo de caudal 015E.
     * Si el raw es negativo (sin dato), muestra "—". Si es cero, muestra "No soportado".
     */
    private void updateFuelRateField(int raw) {
        if (binding == null) {
            return;
        }
        if (raw < 0) {
            binding.debugFuelRateValue.setText(getString(R.string.debug_no_data));
        } else if (raw == 0) {
            // El ECU respondió pero con cero: PID presente pero sin dato útil.
            binding.debugFuelRateValue.setText(getString(R.string.debug_fuelrate_unsupported));
        } else {
            // Convertimos el punto fijo a L/h con 1 decimal para la UI.
            float lh = raw / 20.0f;
            binding.debugFuelRateValue.setText(
                    getString(R.string.debug_float_format, lh));
        }
    }

    /**
     * Actualiza los campos de consumo instantáneo, medio y método activo.
     * Se llama en cada dato recibido y en el bind inicial.
     */
    private void updateFuelFields() {
        if (boundService == null || binding == null) {
            return;
        }

        String noData = getString(R.string.debug_no_data);

        // Método activo
        int method = boundService.getFuelMethod();
        String methodText;
        switch (method) {
            case FuelCalculator.METHOD_FUEL_RATE:
                methodText = getString(R.string.debug_fuel_method_015e);
                break;
            case FuelCalculator.METHOD_MAF:
                methodText = getString(R.string.debug_fuel_method_maf);
                break;
            case FuelCalculator.METHOD_SPEED_DENSITY:
                methodText = getString(R.string.debug_fuel_method_sd);
                break;
            default:
                methodText = getString(R.string.debug_fuel_method_none);
                break;
        }
        binding.debugFuelMethodValue.setText(methodText);

        // Consumo instantáneo L/h
        float instantLh = boundService.getInstantLh();
        if (Float.isNaN(instantLh)) {
            binding.debugInstantLhValue.setText(noData);
        } else {
            binding.debugInstantLhValue.setText(
                    getString(R.string.debug_float_format, instantLh));
        }

        // Consumo instantáneo L/100km (NaN cuando velocidad < umbral)
        float instantL100 = boundService.getInstantL100km();
        if (Float.isNaN(instantL100)) {
            binding.debugInstantL100kmValue.setText(noData);
        } else {
            binding.debugInstantL100kmValue.setText(
                    getString(R.string.debug_float_format, instantL100));
        }

        // Consumo medio L/100km (ventana 5 min)
        float avgL100 = boundService.getAverageL100km();
        if (Float.isNaN(avgL100)) {
            binding.debugAverageL100kmValue.setText(noData);
        } else {
            binding.debugAverageL100kmValue.setText(
                    getString(R.string.debug_float_format, avgL100));
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
