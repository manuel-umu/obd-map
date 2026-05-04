package com.obdmap.launcher.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.obdmap.launcher.R;
import com.obdmap.launcher.databinding.ActivityMainBinding;
import com.obdmap.launcher.gps.GpsManager;
import com.obdmap.launcher.map.MapFileLocator;
import com.obdmap.launcher.map.MapManager;
import com.obdmap.launcher.map.PositionLayer;
import com.obdmap.launcher.prefs.PrefsManager;

import org.mapsforge.core.model.LatLong;

import java.io.File;

/**
 * Activity principal del launcher. Gestiona los permisos en runtime, monta el
 * MapView de Mapsforge, arranca el GPS y maneja la lógica de auto-centrado.
 */
public final class MainActivity extends AppCompatActivity implements GpsManager.PositionListener {

    // Código arbitrario para identificar la respuesta de requestPermissions.
    private static final int REQUEST_PERMISSIONS = 100;

    // Permisos imprescindibles para Fase 1: lectura de SD (mapa) y GPS.
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    private ActivityMainBinding binding;
    private PrefsManager prefsManager;

    // Componentes inicializados sólo cuando los permisos están concedidos
    // y se ha encontrado un archivo .map válido.
    @Nullable private MapManager mapManager;
    @Nullable private GpsManager gpsManager;
    @Nullable private PositionLayer positionLayer;

    // Última posición conocida; se usa para recentrar bajo demanda.
    @Nullable private LatLong lastPosition;

    // Si false, las actualizaciones de GPS NO recentran el mapa (pan manual activo).
    private boolean autoCenter = true;

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

        // Detección de pan manual: en cuanto el usuario mueve el mapa, dejamos
        // de auto-centrar y mostramos el botón "Recentrar".
        binding.mapView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_MOVE && autoCenter) {
                    autoCenter = false;
                    binding.recenterButton.setVisibility(View.VISIBLE);
                }
                // No consumimos el evento: dejamos que MapView siga procesando el gesto.
                return false;
            }
        });

        if (hasAllPermissions()) {
            initMapAndGps();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
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
        for (int i = 0; i < REQUIRED_PERMISSIONS.length; i++) {
            int result = ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[i]);
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Localiza el archivo .map, monta el MapView, añade el marcador de posición
     * y arranca el GPS. Sólo se invoca con todos los permisos concedidos.
     */
    private void initMapAndGps() {
        File mapFile = MapFileLocator.findFirstMapFile();
        if (mapFile == null) {
            binding.statusText.setText(R.string.status_no_map);
            return;
        }
        prefsManager.setMapFilePath(mapFile.getAbsolutePath());

        mapManager = new MapManager(this);
        mapManager.attachToView(binding.mapView, mapFile);

        // Capa de posición con rotación por rumbo. El icono aparece al primer fix GPS.
        positionLayer = new PositionLayer(
                ContextCompat.getDrawable(this, R.drawable.ic_position_arrow));
        binding.mapView.getLayerManager().getLayers().add(positionLayer);

        binding.statusText.setText(getString(R.string.status_map_loaded, mapFile.getName()));

        gpsManager = new GpsManager(this, this);
        try {
            gpsManager.start();
        } catch (SecurityException ex) {
            // No debería ocurrir: ya hemos comprobado permisos.
            binding.statusText.setText(R.string.status_no_permissions);
        }
    }

    // ------------------------------------------------------------------------
    // GpsManager.PositionListener
    // ------------------------------------------------------------------------

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

        // Persistimos la última posición conocida para arranques futuros.
        prefsManager.setLastPosition((float) latitude, (float) longitude);
    }

    @Override
    public void onProviderDisabled() {
        binding.statusText.setText(R.string.status_gps_lost);
    }

    // ------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        // El GPS se para en onPause para ahorrar batería al volver atrás
        // (ej. tras lanzar Ajustes), así que aquí lo reactivamos.
        if (gpsManager != null) {
            try {
                gpsManager.start();
            } catch (SecurityException ignored) {
                // Permisos revocados desde Ajustes: silencioso.
            }
        }
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
        if (mapManager != null) {
            mapManager.destroy();
            mapManager = null;
        }
        binding = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Launcher: el botón atrás no hace nada (no hay donde volver).
    }

    // ------------------------------------------------------------------------

    private void openSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
