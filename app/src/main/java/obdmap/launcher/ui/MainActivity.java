package obdmap.launcher.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.mapsforge.core.model.LatLong;

import java.io.File;
import java.util.Locale;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivityMainBinding;
import obdmap.launcher.gps.GpsManager;
import obdmap.launcher.map.MapDownloadListener;
import obdmap.launcher.map.MapDownloader;
import obdmap.launcher.map.MapFileLocator;
import obdmap.launcher.map.MapManager;
import obdmap.launcher.map.PositionLayer;
import obdmap.launcher.obd.ObdPids;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.service.ObdService;
import obdmap.launcher.service.ObdServiceListener;

/**
 * Pantalla principal del launcher: el mapa.
 *
 * Pide permisos, carga el .map, arranca el GPS y muestra el HUD de consumo.
 */
public final class MainActivity extends AppCompatActivity
        implements GpsManager.PositionListener, ObdServiceListener {

    private static final int REQUEST_PERMISSIONS = 100;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    // El HUD no necesita refrescarse por cada PID. Lo limitamos a 5 Hz.
    private static final long HUD_REFRESH_INTERVAL_MS = 200L;

    private ActivityMainBinding binding;
    private PrefsManager prefsManager;

    @Nullable private MapManager mapManager;
    @Nullable private GpsManager gpsManager;
    @Nullable private PositionLayer positionLayer;
    @Nullable private LatLong lastPosition;
    @Nullable private MapDownloader mapDownloader;
    @Nullable private ObdService boundService;

    private boolean autoCenter = true;
    private boolean serviceBound = false;
    private boolean hudRefreshPending = false;
    private long lastHudRefreshMs = 0L;

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    private final Runnable hudRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            hudRefreshPending = false;
            if (binding == null || boundService == null) {
                return;
            }
            lastHudRefreshMs = SystemClock.uptimeMillis();
            updateHudValue();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);

        binding.emergencyAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSystemSettings();
            }
        });

        binding.openSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        binding.openObdDebugButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, ObdDebugActivity.class));
            }
        });

        binding.recenterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                autoCenter = true;
                binding.recenterButton.setVisibility(View.GONE);
                if (lastPosition != null) {
                    binding.mapView.setCenter(lastPosition);
                }
            }
        });

        binding.mapView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_MOVE && autoCenter) {
                    autoCenter = false;
                    binding.recenterButton.setVisibility(View.VISIBLE);
                }
                return false;
            }
        });

        applyHudVisibility();

        if (hasAllPermissions()) {
            initMapAndGps();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }

        maybeStartObdService();
    }

    @Override
    protected void onStart() {
        super.onStart();

        applyHudVisibility();

        String mac = prefsManager.getObdMac();
        if (mac != null && !mac.isEmpty()) {
            Intent bindIntent = new Intent(this, ObdService.class);
            bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        hudHandler.removeCallbacks(hudRefreshRunnable);
        hudRefreshPending = false;

        if (serviceBound) {
            if (boundService != null) {
                boundService.unregisterServiceListener(MainActivity.this);
            }
            unbindService(serviceConnection);
            serviceBound = false;
            boundService = null;
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (!hasAllPermissions()) {
            binding.statusText.setText(R.string.status_no_permissions);
            return;
        }
        initMapAndGps();
    }

    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            int result = ContextCompat.checkSelfPermission(this, permission);
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Busca el .map y lo carga. Si no existe, arranca la descarga automatica.
     */
    private void initMapAndGps() {
        File mapFile = MapFileLocator.findMapFile(this);
        if (mapFile != null) {
            loadMap(mapFile);
            return;
        }

        if (mapDownloader != null && mapDownloader.isRunning()) {
            return;
        }

        mapDownloader = new MapDownloader();
        binding.statusText.setText(getString(R.string.status_downloading_map, 0));

        mapDownloader.start(this, new MapDownloadListener() {
            @Override
            public void onProgress(int percent) {
                if (binding == null) {
                    return;
                }
                binding.statusText.setText(
                        getString(R.string.status_downloading_map, percent));
            }

            @Override
            public void onComplete(@NonNull File file) {
                if (binding == null) {
                    return;
                }
                prefsManager.setMapFilePath(file.getAbsolutePath());
                loadMap(file);
            }

            @Override
            public void onError(@NonNull String message) {
                if (binding == null) {
                    return;
                }
                binding.statusText.setText(
                        getString(R.string.status_download_failed, message));
            }
        });
    }

    /**
     * Monta el mapa, añade la capa de posicion y arranca el GPS.
     */
    private void loadMap(@NonNull File mapFile) {
        prefsManager.setMapFilePath(mapFile.getAbsolutePath());

        mapManager = new MapManager(this);
        mapManager.attachToView(binding.mapView, mapFile);

        positionLayer = new PositionLayer(
                ContextCompat.getDrawable(this, R.drawable.ic_position_arrow));
        binding.mapView.getLayerManager().getLayers().add(positionLayer);

        binding.statusText.setText(getString(R.string.status_map_loaded, mapFile.getName()));

        gpsManager = new GpsManager(this, this);
        try {
            gpsManager.start();
        } catch (SecurityException ignored) {
            binding.statusText.setText(R.string.status_no_permissions);
        }
    }

    @Override
    public void onPositionUpdate(double latitude, double longitude,
                                 float bearingDegrees, boolean hasBearing, float speedMs) {
        LatLong pos = new LatLong(latitude, longitude);
        lastPosition = pos;

        if (positionLayer != null) {
            positionLayer.updatePosition(pos, bearingDegrees, hasBearing, speedMs);
        }

        if (autoCenter) {
            binding.mapView.setCenter(pos);
        }

        binding.statusText.setText(R.string.status_gps_active);
        prefsManager.setLastPosition((float) latitude, (float) longitude);
    }

    @Override
    public void onProviderDisabled() {
        binding.statusText.setText(R.string.status_gps_lost);
    }

    @Override
    public void onObdStateChanged(@ObdState.State int state) {
        if (binding == null) {
            return;
        }
        if (state != ObdState.READY) {
            hudHandler.removeCallbacks(hudRefreshRunnable);
            hudRefreshPending = false;
            lastHudRefreshMs = 0L;
            binding.hudFuelIndicator.setNoData();
        }
    }

    @Override
    public void onObdDataUpdated(@NonNull String pid, int rawValue) {
        if (binding == null || boundService == null) {
            return;
        }

        if (!ObdPids.SPEED.equals(pid)
                && !ObdPids.FUEL_RATE.equals(pid)
                && !ObdPids.MAF.equals(pid)) {
            return;
        }

        scheduleHudRefresh();
    }

    @Override
    public void onObdSnapshot() {
        if (binding == null || boundService == null) {
            return;
        }
        scheduleHudRefresh();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ObdService.LocalBinder localBinder = (ObdService.LocalBinder) binder;
            boundService = localBinder.getService();
            serviceBound = true;
            boundService.registerServiceListener(MainActivity.this);

            binding.hudContainer.setVisibility(View.VISIBLE);
            hudHandler.removeCallbacks(hudRefreshRunnable);
            hudRefreshPending = false;
            lastHudRefreshMs = 0L;
            scheduleHudRefresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (gpsManager != null) {
            try {
                gpsManager.start();
            } catch (SecurityException ignored) {
            }
        }
        maybeStartObdService();
    }

    @Override
    protected void onPause() {
        if (gpsManager != null) {
            gpsManager.stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        hudHandler.removeCallbacks(hudRefreshRunnable);

        if (mapDownloader != null && mapDownloader.isRunning()) {
            mapDownloader.cancel();
        }
        if (mapManager != null) {
            mapManager.destroy();
            mapManager = null;
        }
        binding = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Launcher: el boton atras no hace nada.
    }

    private void maybeStartObdService() {
        String mac = prefsManager.getObdMac();
        if (mac != null && !mac.isEmpty()) {
            Intent intent = new Intent(this, ObdService.class);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void applyHudVisibility() {
        String mac = prefsManager.getObdMac();
        boolean hasMac = (mac != null && !mac.isEmpty());
        if (!hasMac) {
            hudHandler.removeCallbacks(hudRefreshRunnable);
            hudRefreshPending = false;
            lastHudRefreshMs = 0L;
            binding.hudContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Agrupa varios callbacks OBD en un solo refresco visual.
     * Asi el HUD no se pinta mas de 5 veces por segundo.
     */
    private void scheduleHudRefresh() {
        long now = SystemClock.uptimeMillis();
        long elapsedMs = now - lastHudRefreshMs;

        if (elapsedMs >= HUD_REFRESH_INTERVAL_MS && !hudRefreshPending) {
            lastHudRefreshMs = now;
            updateHudValue();
            return;
        }

        if (hudRefreshPending) {
            return;
        }

        long delayMs = Math.max(0L, HUD_REFRESH_INTERVAL_MS - elapsedMs);
        hudRefreshPending = true;
        hudHandler.postDelayed(hudRefreshRunnable, delayMs);
    }

    /**
     * Actualiza el indicador del HUD con el consumo mas reciente del servicio.
     * Usa L/100km en marcha y L/h cuando vamos casi parados.
     */
    private void updateHudValue() {
        if (binding == null || boundService == null) {
            return;
        }

        float l100 = boundService.getInstantL100km();
        if (!Float.isNaN(l100)) {
            binding.hudFuelIndicator.setUnit(getString(R.string.hud_fuel_unit_l100km));
            binding.hudFuelIndicator.setValueText(String.format(Locale.US, "%.1f", l100));
            return;
        }

        float lh = boundService.getInstantLh();
        if (!Float.isNaN(lh)) {
            binding.hudFuelIndicator.setUnit(getString(R.string.hud_fuel_unit_lh));
            binding.hudFuelIndicator.setValueText(String.format(Locale.US, "%.1f", lh));
            return;
        }

        binding.hudFuelIndicator.setNoData();
    }

    private void openSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
