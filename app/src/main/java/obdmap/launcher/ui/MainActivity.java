package obdmap.launcher.ui;

import android.Manifest;
import android.annotation.SuppressLint;
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

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivityMainBinding;
import obdmap.launcher.gps.GpsManager;
import obdmap.launcher.map.DestinationPickerLayer;
import obdmap.launcher.map.MapDownloadListener;
import obdmap.launcher.map.MapDownloader;
import obdmap.launcher.map.MapFileLocator;
import obdmap.launcher.map.MapManager;
import obdmap.launcher.map.PositionLayer;
import obdmap.launcher.obd.ObdPids;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.routing.NavigationTracker;
import obdmap.launcher.routing.RoadSnapper;
import obdmap.launcher.routing.Route;
import obdmap.launcher.routing.RoutingManager;
import obdmap.launcher.service.ObdService;
import obdmap.launcher.service.ObdServiceListener;
import obdmap.launcher.update.UpdateManager;
import obdmap.launcher.util.DayNightMode;
import obdmap.launcher.util.ManeuverIcons;
import obdmap.launcher.util.PositionPredictor;
import obdmap.launcher.voice.NavVoiceAnnouncer;
import obdmap.launcher.voice.TtsManager;

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

    // Auto actualización OTA
    private final UpdateManager updateManager = new UpdateManager();

    @Nullable private MapManager mapManager;
    @Nullable private GpsManager gpsManager;
    @Nullable private PositionLayer positionLayer;
    @Nullable private DestinationPickerLayer destinationPickerLayer;
    @Nullable private TtsManager ttsManager;
    @Nullable private NavVoiceAnnouncer navVoiceAnnouncer;

    // Coordenadas del pin de destino provisional (long-press, aún no confirmado).
    // Double.NaN cuando no hay pin activo.
    private double pendingPickLat = Double.NaN;
    private double pendingPickLon = Double.NaN;

    // Factor de conversión: metros por grado a 45° de latitud (aproximación equirectangular).
    private static final double METERS_PER_DEG = 111320.0;

    // Umbral en metros por debajo del cual se muestra la distancia en metros en vez de km.
    private static final double DIST_THRESHOLD_M = 1000.0;

    // Última posición conocida
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;

    // Buffer para el resultado del snap-to-road
    private final double[] snapOut = new double[2];

    // Buffer para el resultado de la predicción de posición
    private final double[] predictOut = new double[2];

    @Nullable private MapDownloader mapDownloader;
    @Nullable private ObdService boundService;

    // Última ruta calculada; null si aún no hay ruta
    @Nullable Route currentRoute;

    // Rastrea la posición del usuario sobre la ruta para obtener instrucciones turn-by-turn.
    private final NavigationTracker navigationTracker = new NavigationTracker();

    // Coordenadas del último destino para el que se disparó el cálculo.
    // Evita recalcular en cada fix GPS cuando el destino no ha cambiado.
    private float lastCalculatedDestLat = Float.NaN;
    private float lastCalculatedDestLon = Float.NaN;

    @DayNightMode.Mode
    private int currentDayNightMode;

    private boolean autoCenter = true;
    private boolean serviceBound = false;
    private boolean hudRefreshPending = false;
    private long lastHudRefreshMs = 0L;

    // --- Lead adaptativo en curvas ---
    // Umbral inferior: por debajo de 5°/fix el adelanto es completo (recta).
    private static final float TURN_FULL_LEAD_DEG = 5.0f;
    // Umbral superior: por encima de 25°/fix el adelanto se anula (curva cerrada).
    // En curva la velocidad es baja, así que perder el adelanto es imperceptible.
    private static final float TURN_ZERO_LEAD_DEG = 25.0f;

    // Rumbo del fix anterior para calcular la velocidad angular de giro (°/fix).
    private float prevBearingDeg;
    // false hasta recibir el primer fix con bearing válido.
    private boolean hasPrevBearing = false;

    // Dirty-check para el HUD de navegación: evita redibujar si los valores no cambiaron.
    private int lastNavSign = Integer.MIN_VALUE;
    private String lastNavStreet = null;
    private String lastNavDistance = null;
    private String lastNavRemaining = null;
    private String lastNavEta = null;

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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);
        currentDayNightMode = prefsManager.isNightMode() ? DayNightMode.NIGHT : DayNightMode.DAY;
        ttsManager = new TtsManager(this);
        navVoiceAnnouncer = new NavVoiceAnnouncer(this, ttsManager);

        binding.emergencyAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSystemSettings();
            }
        });

        binding.appSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        binding.openDestinationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, DestinationActivity.class));
            }
        });

        binding.destConfirmGoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmPickedDestination();
            }
        });

        binding.destConfirmCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelPickedDestination();
            }
        });

        binding.dayNightToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleDayNight();
            }
        });

        binding.recenterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                autoCenter = true;
                // Sincronizar el flag en PositionLayer para que vuelva al path
                // de sincronización directa con el viewport (sin interpolación propia).
                if (positionLayer != null) {
                    positionLayer.setAutoCenter(true);
                }
                binding.recenterButton.setVisibility(View.GONE);
                // Centramos inmediatamente si ya tenemos posición.
                if (!Double.isNaN(lastLat) && mapManager != null) {
                    mapManager.centerAt(lastLat, lastLon);
                }
            }
        });

        // Detectar movimiento manual en el mapa.
        // ACTION_MOVE desactiva el seguimiento y muestra el botón de recentrar.
        binding.mapView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_MOVE && autoCenter) {
                    autoCenter = false;
                    // Sincronizar en PositionLayer para que use interpolación propia.
                    if (positionLayer != null) {
                        positionLayer.setAutoCenter(false);
                    }
                    binding.recenterButton.setVisibility(View.VISIBLE);
                }
                // Devolvemos false para que VTM siga procesando el gesto normalmente.
                return false;
            }
        });

        applyHudVisibility();
        applyDayNightToUi();

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
            binding.statusText.setVisibility(View.VISIBLE);
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
        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText(getString(R.string.status_downloading_map, 0));

        mapDownloader.start(this, new MapDownloadListener() {
            @Override
            public void onProgress(int percent) {
                if (binding == null) {
                    return;
                }
                binding.statusText.setVisibility(View.VISIBLE);
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
                binding.statusText.setVisibility(View.VISIBLE);
                binding.statusText.setText(
                        getString(R.string.status_download_failed, message));
            }
        });
    }

    /**
     * Monta el mapa VTM, añade la capa de posicion y arranca el GPS.
     */
    private void loadMap(@NonNull File mapFile) {
        prefsManager.setMapFilePath(mapFile.getAbsolutePath());

        mapManager = new MapManager();
        // Temas claro y oscuro
        mapManager.attachToView(binding.mapView, mapFile, getAssets());

        // PositionLayer necesita el Map de VTM para añadirse a las capas.
        positionLayer = new PositionLayer(
                binding.mapView.map(),
                ContextCompat.getDrawable(this, R.drawable.ic_position_arrow));

        // Capa de selección de destino por long-press. Se añade encima del resto
        // para recibir el gesto antes que otras capas.
        destinationPickerLayer = new DestinationPickerLayer(
                binding.mapView.map(),
                new DestinationPickerLayer.OnDestinationPickedListener() {
                    @Override
                    public void onDestinationPicked(double lat, double lon) {
                        showDestinationConfirmPanel(lat, lon);
                    }
                });

        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText(getString(R.string.status_map_loaded, mapFile.getName()));

        gpsManager = new GpsManager(this, this);
        try {
            gpsManager.start();
        } catch (SecurityException ignored) {
            binding.statusText.setVisibility(View.VISIBLE);
            binding.statusText.setText(R.string.status_no_permissions);
        }
        applyDayNightToUi();
    }

    @Override
    public void onPositionUpdate(double latitude, double longitude,
                                 float bearingDegrees, boolean hasBearing, float speedMs) {

        // Cascada de snap-to-road:
        // 1) Si hay ruta activa, proyectar sobre su polilínea.
        // 2) Si no (o el punto está lejos de la ruta), pegar a la red completa.
        // 3) Si tampoco hay snap válido, usar el GPS crudo.
        // El bearing y la velocidad SIEMPRE son los del GPS original.
        double useLat = latitude;
        double useLon = longitude;

        if (currentRoute != null
                && RoadSnapper.snapToRoute(currentRoute, latitude, longitude,
                                           RoadSnapper.MAX_SNAP_METERS, snapOut)) {
            useLat = snapOut[0];
            useLon = snapOut[1];
        } else {
            RoutingManager rm = RoutingManager.getInstance();
            if (rm.getState() == RoutingManager.STATE_READY
                    && rm.getHopper() != null
                    && RoadSnapper.snapToNetwork(rm.getHopper(), latitude, longitude,
                                                RoadSnapper.MAX_SNAP_METERS,
                                                bearingDegrees, hasBearing, snapOut)) {
                useLat = snapOut[0];
                useLon = snapOut[1];
            }
        }

        // La posición real (snapeada) es la que persiste y alimenta la lógica de ruta.
        lastLat = useLat;
        lastLon = useLon;

        // Predicción de posición (lead/lookahead)
        // Pipeline: snap con pos original -> predict -> snap otra vez con prediccion.

        float turnDeg = 0.0f;
        if (hasBearing && hasPrevBearing) {
            float diff = bearingDegrees - prevBearingDeg;
            while (diff > 180.0f){
                diff -= 360.0f;
            }
            while (diff < -180.0f){
                diff += 360.0f;
            }
            if (diff < 0.0f){
                diff = -diff;
            }
            turnDeg = diff;
        }
        float leadScale;
        if (turnDeg <= TURN_FULL_LEAD_DEG) {
            leadScale = 1.0f;
        } else if (turnDeg >= TURN_ZERO_LEAD_DEG) {
            leadScale = 0.0f;
        } else {
            leadScale = 1.0f - (turnDeg - TURN_FULL_LEAD_DEG)
                    / (TURN_ZERO_LEAD_DEG - TURN_FULL_LEAD_DEG);
        }

        long effectiveLookaheadMs = (long) (PositionPredictor.LOOKAHEAD_MS * leadScale);

        // Actualizar bearing previo solo cuando el GPS reporta bearing valido
        if (hasBearing) {
            prevBearingDeg = bearingDegrees;
            hasPrevBearing = true;
        }

        double renderLat;
        double renderLon;
        boolean hasPrediction = PositionPredictor.predict(useLat, useLon,
                bearingDegrees, hasBearing, speedMs,
                effectiveLookaheadMs,
                PositionPredictor.MAX_LEAD_METERS,
                predictOut);

        if (hasPrediction) {
            // Re-snap del punto predicho: el punto adelantado puede haberse salido
            // lateralmente de la vía si el bearing GPS tenía error angular.
            boolean predSnapped = false;
            if (currentRoute != null) {
                // Con ruta activa: proyectar sobre la polilínea de la ruta.
                predSnapped = RoadSnapper.snapToRoute(currentRoute,
                        predictOut[0], predictOut[1],
                        RoadSnapper.MAX_SNAP_METERS, snapOut);
            }
            if (!predSnapped) {
                // Sin ruta
                RoutingManager rmSnap = RoutingManager.getInstance();
                if (rmSnap.getState() == RoutingManager.STATE_READY
                        && rmSnap.getHopper() != null) {
                    predSnapped = RoadSnapper.snapToNetwork(rmSnap.getHopper(),
                            predictOut[0], predictOut[1],
                            RoadSnapper.MAX_SNAP_METERS,
                            bearingDegrees, hasBearing, snapOut);
                }
            }

            if (predSnapped) {
                // Punto predicho y pegado a la vía
                renderLat = snapOut[0];
                renderLon = snapOut[1];
            } else {
                // El predicho cayó fuera de toda vía (curva cerrada, intersección...):
                // fallback a la posición real ya snapeada para no pintar fuera de la vía.
                renderLat = useLat;
                renderLon = useLon;
            }
        } else {
            // Parado o sin bearing fiable: sin lead, la flecha converge al punto real.
            renderLat = useLat;
            renderLon = useLon;
        }

        // Marcador y viewport reciben el MISMO objetivo de render.
        // En autoCenter el marcador lee el viewport (POSITION_EVENT), así que
        // es imprescindible que ambos apunten al mismo punto para no derivar.
        if (positionLayer != null) {
            positionLayer.setTargetPosition(renderLat, renderLon);
        }
        if (mapManager != null) {
            mapManager.updateCar(renderLat, renderLon, bearingDegrees,
                    hasBearing, speedMs, autoCenter);
        }

        // Actualizar el tracker de navegación y el HUD de maniobra
        if (currentRoute != null) {
            navigationTracker.update(useLat, useLon);
            updateNavHud();
            if (navVoiceAnnouncer != null) {
                navVoiceAnnouncer.onNavUpdate(
                        navigationTracker.currentInstructionIndex,
                        navigationTracker.nextManeuverSign,
                        navigationTracker.nextManeuverName,
                        navigationTracker.distanceToManeuverM);
            }
        } else {
            hideNavHud();
        }

        binding.statusText.setVisibility(View.GONE);
        // Se guarda la posición GPS CRUDA (no la predicha) para que al relanzar
        // la app el mapa arranque desde donde el coche estaba realmente.
        prefsManager.setLastPosition((float) latitude, (float) longitude);

        // Actualizar el badge de velocidad con la lectura GPS más reciente.
        binding.speedBadge.setSpeed(speedMs);

        // Intentar calcular la ruta si hay destino y el grafo está disponible.
        maybeCalculateRoute();
    }

    @Override
    public void onProviderDisabled() {
        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText(R.string.status_gps_lost);
        // Sin proveedor GPS activo: el badge muestra "--".
        binding.speedBadge.setNoData();
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
        // Notificar a VTM que la Activity vuelve al frente (reanuda el renderer GL).
        if (mapManager != null) {
            mapManager.onResume();
        }
        if (gpsManager != null) {
            try {
                gpsManager.start();
            } catch (SecurityException ignored) {
            }
        }
        maybeStartObdService();

        // Comprobación de actualización OTA
        updateManager.checkOnStartup(this);

        // Al volver a primer plano: si el destino cambió mientras estábamos en pausa,
        // resetear para que se recalcule en el próximo fix GPS.
        float destLat = prefsManager.getDestLat();
        float destLon = prefsManager.getDestLon();
        if (destLat != lastCalculatedDestLat || destLon != lastCalculatedDestLon) {
            lastCalculatedDestLat = Float.NaN;
            lastCalculatedDestLon = Float.NaN;
            currentRoute = null;
            // El destino cambió mientras la app estaba en pausa: invalidar el tracker.
            navigationTracker.setRoute(null);
            if (navVoiceAnnouncer != null) {
                navVoiceAnnouncer.reset();
            }
        }

        // Si ya tenemos posición y hay destino, intentamos calcular ahora mismo.
        maybeCalculateRoute();
    }

    @Override
    protected void onPause() {
        if (gpsManager != null) {
            gpsManager.stop();
        }
        // Notificar a VTM que la Activity va al fondo (pausa el renderer GL).
        if (mapManager != null) {
            mapManager.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        hudHandler.removeCallbacks(hudRefreshRunnable);

        if (mapDownloader != null && mapDownloader.isRunning()) {
            mapDownloader.cancel();
        }
        if (positionLayer != null) {
            // Desregistrar el UpdateListener para que el mapa no retenga la Activity.
            positionLayer.detach();
            positionLayer = null;
        }
        // La capa de picker no tiene recursos propios que liberar; basta con anular la referencia.
        destinationPickerLayer = null;
        if (mapManager != null) {
            mapManager.destroy();
            mapManager = null;
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
            ttsManager = null;
        }
        navVoiceAnnouncer = null;
        binding = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Launcher: el botón atrás no hace nada.
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

    /**
     * Dispara el cálculo de ruta si se dan las tres condiciones:
     * hay posición GPS, hay destino en prefs, y el grafo está READY.
     * Si el grafo no está READY pero hay destino, arranca la carga.
     * No recalcula si el destino no ha cambiado respecto al último cálculo.
     */
    private void maybeCalculateRoute() {
        float destLat = prefsManager.getDestLat();
        float destLon = prefsManager.getDestLon();

        if (Float.isNaN(destLat) || Float.isNaN(destLon)) {
            // No hay destino (o se borró): si quedaba una ruta dibujada, la quitamos.
            // No depende del GPS, por eso va antes del guard de posición.
            if (currentRoute != null) {
                currentRoute = null;
                // Resetear el tracker al cancelar la ruta.
                navigationTracker.setRoute(null);
                if (navVoiceAnnouncer != null) {
                    navVoiceAnnouncer.reset();
                }
                lastCalculatedDestLat = Float.NaN;
                lastCalculatedDestLon = Float.NaN;
                if (mapManager != null) {
                    mapManager.clearRoute();
                }
                hideNavHud();
            }
            return;
        }

        if (Double.isNaN(lastLat) || Double.isNaN(lastLon)) {
            // Hay destino pero aún no hay posición GPS: no podemos calcular todavía.
            return;
        }

        // Evitar recalcular si ya se calculó para este mismo destino.
        if (destLat == lastCalculatedDestLat && destLon == lastCalculatedDestLon) {
            return;
        }

        RoutingManager rm = RoutingManager.getInstance();

        if (rm.getState() == RoutingManager.STATE_READY) {
            launchRouteCalculation(destLat, destLon);
            return;
        }

        if (rm.getState() == RoutingManager.STATE_LOADING) {
            // Ya está cargando; cuando termine disparará el cálculo via onRoutingReady.
            return;
        }

        // Grafo no cargado aún: arrancamos la carga. El callback disparará el cálculo.
        rm.startLoading(this, new RoutingManager.RoutingListener() {
            @Override
            public void onRoutingReady() {
                if (binding == null) {
                    return;
                }
                float dLat = prefsManager.getDestLat();
                float dLon = prefsManager.getDestLon();
                if (!Float.isNaN(dLat) && !Float.isNaN(dLon)) {
                    launchRouteCalculation(dLat, dLon);
                }
            }

            @Override
            public void onRoutingError(@NonNull String message) {
                if (binding == null) {
                    return;
                }
                binding.statusText.setVisibility(View.VISIBLE);
                binding.statusText.setText(getString(R.string.route_error, message));
            }

            @Override
            public void onRoutingProgress(@NonNull String status) {
                if (binding != null) {
                    binding.statusText.setVisibility(View.VISIBLE);
                    binding.statusText.setText(status);
                }
            }
        });
    }

    // Lanza el cálculo real de la ruta en RoutingManager.
    private void launchRouteCalculation(final float destLat, final float destLon) {
        // Marcamos ya el destino calculado para no repetir si llega otro fix antes de que termine.
        lastCalculatedDestLat = destLat;
        lastCalculatedDestLon = destLon;

        RoutingManager.getInstance().calculateRoute(
                lastLat, lastLon,
                destLat, destLon,
                new RoutingManager.RouteCallback() {
                    @Override
                    public void onRouteReady(@NonNull Route route) {
                        if (binding == null) {
                            return;
                        }
                        currentRoute = route;
                        navigationTracker.setRoute(currentRoute);

                        // Dibujar la polilínea de la ruta sobre el mapa VTM.
                        if (mapManager != null) {
                            mapManager.showRoute(route.lats, route.lons);
                        }

                        // Distancia en km con 1 decimal, tiempo en minutos enteros.
                        double km = route.distanceMeters / 1000.0;
                        long minutes = route.timeMs / 60000L;

                        binding.statusText.setVisibility(View.VISIBLE);
                        binding.statusText.setText(getString(
                                R.string.route_summary,
                                String.format(Locale.US, "%.1f", km),
                                String.valueOf(minutes)));

                        if (ttsManager != null) {
                            ttsManager.speak(getString(R.string.voice_route_started));
                        }
                    }

                    @Override
                    public void onRouteError(@NonNull String message) {
                        if (binding == null) {
                            return;
                        }
                        binding.statusText.setVisibility(View.VISIBLE);
                        binding.statusText.setText(getString(R.string.route_error, message));
                    }
                });
    }

    /**
     * Formatea una distancia en metros para mostrar en el HUD de navegación.
     * Por debajo de 1 km: redondea a pasos de 10 m (o 50 m si supera 500 m).
     * Por encima de 1 km: usa km con 1 decimal.
     *
     * @param meters distancia en metros
     * @return cadena lista para mostrar (p. ej. "300 m", "1,2 km")
     */
    private String formatNavDistance(double meters) {
        if (meters < 1000.0) {
            int m = (int) meters;
            int rounded;
            if (m >= 500) {
                // Redondear a 50 m para distancias medias
                rounded = ((m + 25) / 50) * 50;
            } else {
                // Redondear a 10 m para distancias cortas
                rounded = ((m + 5) / 10) * 10;
            }
            return getString(R.string.nav_distance_meters, rounded);
        } else {
            float km = (float) (meters / 1000.0);
            return getString(R.string.nav_distance_km, km);
        }
    }

    /**
     * Actualiza el panel de maniobra y la barra de resumen con los datos actuales del tracker.
     */
    private void updateNavHud() {
        if (binding == null) {
            return;
        }

        // --- Panel de maniobra superior ---
        boolean hasManuever = (navigationTracker.currentInstructionIndex >= 0
                && navigationTracker.distanceToManeuverM >= 0);

        if (!hasManuever) {
            binding.navManeuverPanel.setVisibility(View.GONE);
        } else {
            binding.navManeuverPanel.setVisibility(View.VISIBLE);
            int sign = navigationTracker.nextManeuverSign;
            if (sign != lastNavSign) {
                lastNavSign = sign;
                binding.navManeuverIcon.setImageResource(ManeuverIcons.drawableForSign(sign));
            }

            String distStr = formatNavDistance(navigationTracker.distanceToManeuverM);
            if (!distStr.equals(lastNavDistance)) {
                lastNavDistance = distStr;
                binding.navManeuverDistance.setText(distStr);
            }

            String street = navigationTracker.nextManeuverName;
            if (street == null) {
                street = "";
            }
            if (!street.equals(lastNavStreet)) {
                lastNavStreet = street;
                if (street.isEmpty()) {
                    binding.navManeuverStreet.setVisibility(View.GONE);
                } else {
                    binding.navManeuverStreet.setVisibility(View.VISIBLE);
                    binding.navManeuverStreet.setText(street);
                }
            }
        }

        // --- Barra inferior de resumen ---
        double remaining = navigationTracker.distanceRemainingM;

        if (remaining <= 0.0) {
            binding.navSummaryBar.setVisibility(View.GONE);
            return;
        }

        binding.navSummaryBar.setVisibility(View.VISIBLE);

        String remStr = formatNavDistance(remaining);
        if (!remStr.equals(lastNavRemaining)) {
            lastNavRemaining = remStr;
            binding.navRemainingDistance.setText(remStr);
        }

        // Hora actual + tiempo restante
        long nowMs = System.currentTimeMillis();
        long arrivalMs = nowMs + navigationTracker.timeRemainingMs;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(arrivalMs);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        long minutesRemaining = navigationTracker.timeRemainingMs / 60000L;

        String etaStr;
        if (minutesRemaining > 0) {
            etaStr = getString(R.string.nav_time_remaining, (int) minutesRemaining)
                    + " · "
                    + getString(R.string.nav_eta,
                            String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
        } else {
            etaStr = getString(R.string.nav_eta,
                    String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
        }
        if (!etaStr.equals(lastNavEta)) {
            lastNavEta = etaStr;
            binding.navEta.setText(etaStr);
        }
    }

    /**
     * Oculta todos los paneles del HUD de navegación
     */
    private void hideNavHud() {
        if (binding == null) {
            return;
        }
        binding.navManeuverPanel.setVisibility(View.GONE);
        binding.navSummaryBar.setVisibility(View.GONE);
        lastNavSign = Integer.MIN_VALUE;
        lastNavStreet = null;
        lastNavDistance = null;
        lastNavRemaining = null;
        lastNavEta = null;
    }

    private void toggleDayNight() {
        currentDayNightMode = (currentDayNightMode == DayNightMode.NIGHT)
                ? DayNightMode.DAY : DayNightMode.NIGHT;
        prefsManager.setNightMode(currentDayNightMode == DayNightMode.NIGHT);
        applyDayNightToUi();
    }

    private void applyDayNightToUi() {
        boolean isNight = (currentDayNightMode == DayNightMode.NIGHT);
        // El texto del botón muestra el modo al que se CAMBIARÁ al pulsarlo.
        binding.dayNightToggleButton.setText(isNight
                ? R.string.toggle_day_mode : R.string.toggle_night_mode);
        // Fondo semitransparente del HUD: más oscuro de noche, gris claro de día.
        binding.hudContainer.setBackgroundColor(
                isNight ? 0xCC101418 : 0xCCF5F5F5);
        // Tema del mapa VTM.
        if (mapManager != null) {
            mapManager.applyDayNightTheme(currentDayNightMode);
        }
        // Paleta de colores del badge de velocidad.
        binding.speedBadge.applyNightMode(isNight);
        // HUD de navegación: fondo y colores adaptados al modo día/noche
        int navBg = isNight ? 0xCC101418 : 0xCCF5F5F5;
        int navTextPrimary = isNight ? 0xFFE6E6E6 : 0xFF212121;
        int navTextSecondary = isNight ? 0xFFB0B0B0 : 0xFF757575;
        int navIconTint = isNight ? 0xFFE6E6E6 : 0xFF212121;
        binding.navManeuverPanel.setBackgroundColor(navBg);
        binding.navManeuverDistance.setTextColor(navTextPrimary);
        binding.navManeuverStreet.setTextColor(navTextSecondary);
        binding.navManeuverIcon.setColorFilter(navIconTint);
        binding.navSummaryBar.setBackgroundColor(navBg);
        binding.navRemainingDistance.setTextColor(navTextPrimary);
        binding.navEta.setTextColor(navTextPrimary);
    }

    private void openSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * Muestra el panel de confirmación con la distancia en línea recta al punto tocado.
     * La distancia se calcula con la aproximación equirectangular (suficientemente
     * precisa para distancias de <200 km en España).
     *
     * @param lat latitud del punto elegido por long-press
     * @param lon longitud del punto elegido por long-press
     */
    private void showDestinationConfirmPanel(double lat, double lon) {
        if (binding == null) {
            return;
        }
        pendingPickLat = lat;
        pendingPickLon = lon;

        // Calcular distancia en línea recta solo si hay posición GPS.
        if (!Double.isNaN(lastLat) && !Double.isNaN(lastLon)) {
            double dLat = lat - lastLat;
            double dLon = lon - lastLon;
            // cos(lat) en radianes para corregir la distorsión longitudinal.
            double cosLat = Math.cos(Math.toRadians(lastLat));
            double distM = METERS_PER_DEG * Math.sqrt(dLat * dLat + (dLon * dLon * cosLat * cosLat));

            String distText;
            if (distM < DIST_THRESHOLD_M) {
                distText = getString(R.string.dest_confirm_distance, distM, getString(R.string.unit_meters));
            } else {
                distText = getString(R.string.dest_confirm_distance, distM / 1000.0, getString(R.string.unit_km));
            }
            binding.destConfirmText.setText(distText);
        } else {
            binding.destConfirmText.setText(R.string.dest_confirm_no_gps);
        }

        binding.destConfirmPanel.setVisibility(View.VISIBLE);
    }

    /**
     * Confirma el pin provisional: persiste el destino y lanza el cálculo de ruta.
     */
    private void confirmPickedDestination() {
        if (binding == null || Double.isNaN(pendingPickLat) || Double.isNaN(pendingPickLon)) {
            return;
        }
        // Reutiliza exactamente el mismo setter que usa DestinationActivity.
        prefsManager.setDestination((float) pendingPickLat, (float) pendingPickLon);

        // Resetear el cache para forzar el recálculo con el nuevo destino.
        lastCalculatedDestLat = Float.NaN;
        lastCalculatedDestLon = Float.NaN;

        binding.destConfirmPanel.setVisibility(View.GONE);

        // Lanzar el cálculo reutilizando el pipeline completo.
        maybeCalculateRoute();
    }

    /**
     * Cancela la selección provisional: oculta el pin y el panel sin tocar el destino guardado.
     */
    private void cancelPickedDestination() {
        if (binding == null) {
            return;
        }
        pendingPickLat = Double.NaN;
        pendingPickLon = Double.NaN;

        if (destinationPickerLayer != null) {
            destinationPickerLayer.hidePin();
        }
        binding.destConfirmPanel.setVisibility(View.GONE);
    }
}
