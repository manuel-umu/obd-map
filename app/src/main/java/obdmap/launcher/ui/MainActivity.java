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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Calendar;
import java.util.List;
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
import obdmap.launcher.map.RegionData;
import obdmap.launcher.map.SavedPlacesLayer;
import obdmap.launcher.obd.ObdPids;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.prefs.SavedPlace;
import obdmap.launcher.prefs.SavedPlacesStore;
import obdmap.launcher.routing.NavigationTracker;
import obdmap.launcher.routing.RoadMatcher;
import obdmap.launcher.routing.RoadSnapper;
import obdmap.launcher.routing.Route;
import obdmap.launcher.routing.RoutingManager;
import obdmap.launcher.service.ObdService;
import obdmap.launcher.service.ObdServiceListener;
import obdmap.launcher.update.UpdateManager;
import obdmap.launcher.util.BearingPredictor;
import obdmap.launcher.util.ButtonStyler;
import obdmap.launcher.util.DayNightMode;
import obdmap.launcher.util.ManeuverIcons;
import obdmap.launcher.util.PerfMonitor;
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

    // Periodo mínimo entre escrituras de la última posición: apply() reescribe el XML entero.
    private static final long POSITION_SAVE_INTERVAL_MS = 5000L;

    // Fracción de la altura de la vista donde VTM dibuja el coche (setMapViewCenter 0.5).
    private static final float CAR_SCREEN_Y_RATIO = 0.75f;

    private ActivityMainBinding binding;
    private PrefsManager prefsManager;
    private SavedPlacesStore savedPlacesStore;

    // Auto actualización OTA
    private final UpdateManager updateManager = new UpdateManager();

    @Nullable private MapManager mapManager;
    @Nullable private GpsManager gpsManager;
    @Nullable private PositionLayer positionLayer;
    @Nullable private DestinationPickerLayer destinationPickerLayer;
    @Nullable private SavedPlacesLayer savedPlacesLayer;
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

    // Última posición GPS cruda y momento en que se persistió por última vez.
    private double lastRawLat = Double.NaN;
    private double lastRawLon = Double.NaN;
    private long lastPositionSaveMs = 0L;

    // Buffer para el resultado del snap-to-road
    private final double[] snapOut = new double[2];

    // Map-matcher de la posición real
    private final RoadMatcher roadMatcher = new RoadMatcher();

    // Map-matcher del punto predicho, independiente para que pueda avanzar de arista
    private final RoadMatcher leadMatcher = new RoadMatcher();

    // Buffer para el resultado de la predicción de posición
    private final double[] predictOut = new double[2];

    // Buffer para el diagnóstico de snap (lat pegada, lon pegada, dist a arista)
    private final double[] diagOut = new double[3];

    // Estado del overlay de diagnóstico, leído de prefs en cada onResume.
    private boolean debugOverlayEnabled = false;

    // Muestreo de FPS, GC y heap para el overlay de rendimiento.
    private final PerfMonitor perfMonitor = new PerfMonitor();

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

    // Cada cuánto se reevalúa el día/noche automático durante la marcha.
    private static final long DAY_NIGHT_CHECK_INTERVAL_MS = 60_000L;
    private long lastDayNightCheckMs = 0L;

    private boolean autoCenter = true;
    private boolean serviceBound = false;
    private boolean hudRefreshPending = false;
    private long lastHudRefreshMs = 0L;

    // --- Lead adaptativo en curvas ---
    private static final float TURN_FULL_LEAD_DEG_S = 30.0f;
    private static final float TURN_ZERO_LEAD_DEG_S = 90.0f;

    // Estimador de velocidad angular y extrapolador de rumbo para el render.
    private final BearingPredictor bearingPredictor = new BearingPredictor();

    // Dirty-check para el HUD de navegación: evita redibujar si los valores no cambiaron.
    private int lastNavSign = Integer.MIN_VALUE;
    private String lastNavStreet = null;
    private String lastNavDistance = null;
    private String lastNavRemaining = null;
    private String lastNavEta = null;

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    private final Runnable hudRefreshRunnable = () -> {
        hudRefreshPending = false;
        if (binding == null || boundService == null) {
            return;
        }
        lastHudRefreshMs = SystemClock.uptimeMillis();
        updateHudValue();
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);
        savedPlacesStore = new SavedPlacesStore(prefsManager);
        currentDayNightMode = prefsManager.isNightMode() ? DayNightMode.NIGHT : DayNightMode.DAY;
        ttsManager = new TtsManager(this);
        navVoiceAnnouncer = new NavVoiceAnnouncer(this, ttsManager);

        binding.appSettingsButton.setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        binding.destConfirmGoButton.setOnClickListener(v -> confirmPickedDestination());
        binding.destConfirmCancelButton.setOnClickListener(v -> cancelPickedDestination());
        binding.destConfirmScrim.setOnClickListener(v -> cancelPickedDestination());
        binding.cancelRouteButton.setOnClickListener(v -> cancelActiveRoute());
        binding.recenterButton.setOnClickListener(v -> recenterOnPosition());
        binding.destFavoriteButton.setOnClickListener(v -> togglePendingPickFavorite());
        binding.favoritesButton.setOnClickListener(v -> toggleFavoritesPanel());

        // Detectar movimiento manual en el mapa.
        // ACTION_MOVE desactiva el seguimiento y muestra el botón de recentrar.
        binding.mapView.setOnTouchListener((view, event) -> {
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
        });

        // La flecha overlay se recoloca con cada layout del mapa (arranque, rotación).
        binding.mapView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                        -> placeCarArrowOverlay());

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
        RegionData region = RegionData.byIdOrDefault(prefsManager.getActiveRegionId());

        File mapFile = MapFileLocator.findMapFile(this, region);
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

        mapDownloader.start(this, region, new MapDownloadListener() {
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

        // Antes de positionLayer: así la flecha del coche se dibuja sobre los corazones.
        savedPlacesLayer = new SavedPlacesLayer(
                binding.mapView.map(),
                ContextCompat.getDrawable(this, R.drawable.ic_heart_filled));
        savedPlacesLayer.setPlaces(savedPlacesStore.load());

        // PositionLayer necesita el Map de VTM para añadirse a las capas.
        positionLayer = new PositionLayer(
                binding.mapView.map(),
                ContextCompat.getDrawable(this, R.drawable.ic_position_arrow));
        positionLayer.setOverlayArrow(binding.carArrowOverlay);
        positionLayer.setPerfMonitor(perfMonitor);
        placeCarArrowOverlay();

        // Capa de selección de destino por long-press. Se añade encima del resto
        // para recibir el gesto antes que otras capas.
        destinationPickerLayer = new DestinationPickerLayer(
                binding.mapView.map(), this::showDestinationConfirmPanel);

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

        // Precargar el grafo para que el snap-to-road funcione en conducción libre
        preloadGraphForSnapping();
    }

    /**
     * Arranca la carga del grafo de GraphHopper en segundo plano
     */
    private void preloadGraphForSnapping() {
        RoutingManager.getInstance().startLoading(this, new RoutingManager.RoutingListener() {
            @Override
            public void onRoutingReady() {
            }

            @Override
            public void onRoutingError(@NonNull String message) {
            }
            @Override
            public void onRoutingProgress(@NonNull String status) {
            }
        });
    }

    @Override
    public void onPositionUpdate(double latitude, double longitude,
                                 float bearingDegrees, boolean hasBearing, float speedMs) {
        perfMonitor.countGpsFix();
        long tFix = perfMonitor.phaseStart();

        // Cascada de snap-to-road:
        // 1) Si hay ruta activa, proyectar sobre su polilínea.
        // 2) Si no (o el punto está lejos de la ruta), pegar a la red completa.
        // 3) Si tampoco hay snap válido, usar el GPS crudo.
        // El bearing y la velocidad SIEMPRE son los del GPS original.
        double useLat = latitude;
        double useLon = longitude;

        // Semilla de búsqueda para los snaps: es el índice del fix ANTERIOR porque
        // navigationTracker.update() se llama al final de este método. Es lo correcto.
        int segHint = navigationTracker.getCurrentSegmentIndex();

        long tSnap = perfMonitor.phaseStart();
        boolean routeSnapped = currentRoute != null
                && RoadSnapper.snapToRoute(currentRoute, latitude, longitude,
                                           RoadSnapper.MAX_SNAP_METERS, segHint, snapOut);
        perfMonitor.phaseEnd(PerfMonitor.PHASE_SNAP, tSnap);

        if (routeSnapped) {
            useLat = snapOut[0];
            useLon = snapOut[1];
        } else {
            RoutingManager rm = RoutingManager.getInstance();
            if (rm.getState() == RoutingManager.STATE_READY && rm.getHopper() != null) {
                long tMatch = perfMonitor.phaseStart();
                boolean matched = roadMatcher.match(rm.getHopper(), latitude, longitude,
                                                    bearingDegrees, hasBearing,
                                                    RoadSnapper.MAX_SNAP_METERS, snapOut);
                perfMonitor.phaseEnd(PerfMonitor.PHASE_MATCH, tMatch);
                if (matched) {
                    useLat = snapOut[0];
                    useLon = snapOut[1];
                }
            }
        }

        // La posición real (snapeada) es la que persiste y alimenta la lógica de ruta.
        lastLat = useLat;
        lastLon = useLon;

        // Predicción de posición (lead/lookahead)
        // Pipeline: snap con pos original -> predict -> snap otra vez con prediccion.

        boolean bearingUsable = hasBearing && speedMs >= PositionPredictor.MIN_PREDICT_SPEED_MS;
        bearingPredictor.update(bearingDegrees, bearingUsable, SystemClock.elapsedRealtime());

        float turnRate = Math.abs(bearingPredictor.getTurnRateDegS());
        float leadScale;
        if (turnRate <= TURN_FULL_LEAD_DEG_S) {
            leadScale = 1.0f;
        } else if (turnRate >= TURN_ZERO_LEAD_DEG_S) {
            leadScale = 0.0f;
        } else {
            leadScale = 1.0f - (turnRate - TURN_FULL_LEAD_DEG_S)
                    / (TURN_ZERO_LEAD_DEG_S - TURN_FULL_LEAD_DEG_S);
        }

        long effectiveLookaheadMs = (long) (PositionPredictor.LOOKAHEAD_MS * leadScale);

        // Rumbo extrapolado: solo para pintar flecha y rotar el mapa
        float renderBearing = bearingPredictor.predictBearing(bearingDegrees);

        double renderLat;
        double renderLon;
        long tPredict = perfMonitor.phaseStart();
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
                        RoadSnapper.MAX_SNAP_METERS, segHint, snapOut);
            }
            if (!predSnapped) {
                // Sin ruta
                RoutingManager rmSnap = RoutingManager.getInstance();
                if (rmSnap.getState() == RoutingManager.STATE_READY
                        && rmSnap.getHopper() != null) {
                    // Primero se confina el punto adelantado a la vía donde está el coche
                    predSnapped = roadMatcher.snapAhead(predictOut[0], predictOut[1],
                            bearingDegrees, hasBearing,
                            RoadSnapper.MAX_SNAP_METERS, snapOut);
                    if (!predSnapped) {
                        predSnapped = leadMatcher.match(rmSnap.getHopper(),
                                predictOut[0], predictOut[1],
                                bearingDegrees, hasBearing,
                                RoadSnapper.MAX_SNAP_METERS, snapOut);
                    }
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
        perfMonitor.phaseEnd(PerfMonitor.PHASE_PREDICT, tPredict);

        // Marcador y viewport reciben el MISMO objetivo de render.
        // En autoCenter el marcador lee el viewport (POSITION_EVENT), así que
        // es imprescindible que ambos apunten al mismo punto para no derivar.
        if (positionLayer != null) {
            positionLayer.setTargetPosition(renderLat, renderLon);
            positionLayer.setBearing(renderBearing, hasBearing, speedMs);
        }
        if (mapManager != null) {
            mapManager.updateCar(renderLat, renderLon, renderBearing,
                    hasBearing, speedMs, autoCenter);
        }

        updateDebugOverlay(latitude, longitude, useLat, useLon, renderLat, renderLon);

        // Actualizar el tracker de navegación y el HUD de maniobra
        long tNav = perfMonitor.phaseStart();
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
        perfMonitor.phaseEnd(PerfMonitor.PHASE_NAV, tNav);

        // Se guarda la posición GPS CRUDA (no la predicha) para que al relanzar
        // la app el mapa arranque desde donde el coche estaba realmente.
        lastRawLat = latitude;
        lastRawLon = longitude;
        long tPrefs = perfMonitor.phaseStart();
        saveLastPositionThrottled();
        perfMonitor.phaseEnd(PerfMonitor.PHASE_PREFS, tPrefs);

        long tHud = perfMonitor.phaseStart();
        binding.statusText.setVisibility(View.GONE);
        // Actualizar el badge de velocidad con la lectura GPS más reciente.
        binding.speedBadge.setSpeed(speedMs);
        perfMonitor.phaseEnd(PerfMonitor.PHASE_HUD, tHud);

        maybeRefreshDayNight();

        // Intentar calcular la ruta si hay destino y el grafo está disponible.
        long tRoute = perfMonitor.phaseStart();
        maybeCalculateRoute();
        perfMonitor.phaseEnd(PerfMonitor.PHASE_PREFS, tRoute);

        perfMonitor.phaseEnd(PerfMonitor.PHASE_FIX_TOTAL, tFix);
        perfMonitor.countGpsFixDone();
    }

    /**
     * Reevalúa el modo día/noche cada minuto
     */
    private void maybeRefreshDayNight() {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastDayNightCheckMs < DAY_NIGHT_CHECK_INTERVAL_MS) {
            return;
        }
        lastDayNightCheckMs = nowMs;

        int mode = prefsManager.isNightMode() ? DayNightMode.NIGHT : DayNightMode.DAY;
        if (mode != currentDayNightMode) {
            currentDayNightMode = mode;
            applyDayNightToUi();
        }
    }
    /** Persiste la última posición como mucho una vez cada POSITION_SAVE_INTERVAL_MS. */
    private void saveLastPositionThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (lastPositionSaveMs != 0L && (now - lastPositionSaveMs) < POSITION_SAVE_INTERVAL_MS) {
            return;
        }
        lastPositionSaveMs = now;
        prefsManager.setLastPosition((float) lastRawLat, (float) lastRawLon);
    }

    private void updateDebugOverlay(double rawLat, double rawLon,
                                    double snapLat, double snapLon,
                                    double renderLat, double renderLon) {
        if (binding == null || !debugOverlayEnabled) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.US, "GPS %.6f %.6f\n", rawLat, rawLon));
        sb.append(String.format(Locale.US, "Peg %.6f %.6f\n", snapLat, snapLon));
        sb.append(String.format(Locale.US, "Pin %.6f %.6f\n", renderLat, renderLon));

        RoutingManager rm = RoutingManager.getInstance();
        if (rm.getState() == RoutingManager.STATE_READY && rm.getHopper() != null
                && RoadSnapper.snapDiagnostic(rm.getHopper(), rawLat, rawLon, diagOut)) {
            sb.append(String.format(Locale.US, "arista %.1f m\n", diagOut[2]));
        } else {
            sb.append("arista  -- (sin grafo)\n");
        }

        double desfPeg = metersBetween(rawLat, rawLon, snapLat, snapLon);
        double desfPin = metersBetween(rawLat, rawLon, renderLat, renderLon);
        sb.append(String.format(Locale.US, "desf peg %.1f  pin %.1f", desfPeg, desfPin));

        binding.debugOverlay.setText(sb.toString());
    }

    /** Sincroniza el overlay de diagnóstico con la preferencia de Ajustes. */
    private void applyDebugOverlayPref() {
        debugOverlayEnabled = prefsManager.isDebugOverlayEnabled();
        if (binding != null) {
            binding.debugOverlay.setVisibility(
                    debugOverlayEnabled ? View.VISIBLE : View.GONE);
        }
    }

    /** Sincroniza el overlay de rendimiento con la preferencia de Ajustes. */
    private void applyPerfOverlayPref() {
        boolean enabled = prefsManager.isPerfOverlayEnabled();
        if (binding != null) {
            binding.perfOverlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (enabled) {
            perfMonitor.setReportListener(report -> {
                if (binding != null) {
                    binding.perfOverlay.setText(report);
                }
            });
            perfMonitor.start();
        } else {
            perfMonitor.setReportListener(null);
            perfMonitor.stop();
        }
    }

    /** Distancia aproximada en metros entre dos coordenadas (equirectangular). */
    private static double metersBetween(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = (lat2 - lat1) * METERS_PER_DEG;
        double dLon = (lon2 - lon1) * METERS_PER_DEG * Math.cos(Math.toRadians(lat1));
        return Math.sqrt(dLat * dLat + dLon * dLon);
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
        applyDebugOverlayPref();
        applyPerfOverlayPref();
        // El modo día/noche se cambia desde Ajustes: releerlo al volver al frente.
        currentDayNightMode = prefsManager.isNightMode() ? DayNightMode.NIGHT : DayNightMode.DAY;
        applyDayNightToUi();

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
        perfMonitor.stop();
        if (gpsManager != null) {
            gpsManager.stop();
        }
        // La escritura por fix va limitada: al salir se guarda el último fix sí o sí.
        if (!Double.isNaN(lastRawLat)) {
            lastPositionSaveMs = SystemClock.elapsedRealtime();
            prefsManager.setLastPosition((float) lastRawLat, (float) lastRawLon);
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
        // Ni el picker ni la capa de sitios tienen recursos propios que liberar.
        destinationPickerLayer = null;
        savedPlacesLayer = null;
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

    /**
     * Reactiva el auto-centrado tras un pan manual: sincroniza el flag con
     * PositionLayer, oculta el botón y centra ya mismo si hay posición.
     */
    private void recenterOnPosition() {
        autoCenter = true;
        if (positionLayer != null) {
            positionLayer.setAutoCenter(true);
        }
        binding.recenterButton.setVisibility(View.GONE);
        if (!Double.isNaN(lastLat) && mapManager != null) {
            mapManager.centerAt(lastLat, lastLon);
        }
    }

    /**
     * Baja la flecha overlay hasta el punto de la pantalla donde VTM dibuja el coche.
     * Solo en cada layout del mapa, nunca por frame.
     */
    private void placeCarArrowOverlay() {
        if (binding == null) {
            return;
        }
        int mapHeight = binding.mapView.getHeight();
        if (mapHeight == 0) {
            return;
        }
        View arrow = binding.carArrowOverlay;
        int arrowHeight = arrow.getHeight();
        if (arrowHeight <= 0) {
            // Empieza GONE, así que puede no estar medida: su alto de layout es fijo.
            arrowHeight = arrow.getLayoutParams().height;
        }
        arrow.setTranslationY(mapHeight * CAR_SCREEN_Y_RATIO - arrowHeight / 2f);
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
                        updateCancelRouteButton();

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

        updateCancelRouteButton();

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
        updateCancelRouteButton();
        lastNavSign = Integer.MIN_VALUE;
        lastNavStreet = null;
        lastNavDistance = null;
        lastNavRemaining = null;
        lastNavEta = null;
    }

    private void applyDayNightToUi() {
        boolean isNight = (currentDayNightMode == DayNightMode.NIGHT);
        // Botones sobre el mapa: son los únicos que ven el cambio de tema.
        ButtonStyler.applySecondary(binding.appSettingsButton, isNight);
        ButtonStyler.applySecondary(binding.cancelRouteButton, isNight);
        ButtonStyler.applySecondary(binding.destConfirmCancelButton, isNight);
        ButtonStyler.applySecondaryIcon(binding.favoritesButton, isNight);
        ButtonStyler.applySecondaryIcon(binding.destFavoriteButton, isNight);
        ButtonStyler.applyPrimary(binding.recenterButton);
        ButtonStyler.applyPrimary(binding.destConfirmGoButton);
        // Fondo semitransparente del HUD: más oscuro de noche, gris claro de día.
        binding.hudContainer.setBackgroundColor(
                isNight ? 0xCC101418 : 0xCCF5F5F5);
        binding.favoritesPanel.setBackgroundColor(
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

        binding.favoritesPanel.setVisibility(View.GONE);
        binding.destConfirmScrim.setVisibility(View.VISIBLE);
        binding.destConfirmPanel.setVisibility(View.VISIBLE);
        updateFavoriteToggleIcon();
    }

    /**
     * Confirma el pin provisional: persiste el destino y lanza el cálculo de ruta.
     */
    private void confirmPickedDestination() {
        if (binding == null || Double.isNaN(pendingPickLat) || Double.isNaN(pendingPickLon)) {
            return;
        }
        prefsManager.setDestination((float) pendingPickLat, (float) pendingPickLon);

        // Resetear el cache para forzar el recálculo con el nuevo destino.
        lastCalculatedDestLat = Float.NaN;
        lastCalculatedDestLon = Float.NaN;

        binding.destConfirmScrim.setVisibility(View.GONE);
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
        binding.destConfirmScrim.setVisibility(View.GONE);
        binding.destConfirmPanel.setVisibility(View.GONE);
    }

    /** Borra el destino guardado y retira la ruta y el pin del mapa. */
    private void cancelActiveRoute() {
        prefsManager.clearDestination();
        lastCalculatedDestLat = Float.NaN;
        lastCalculatedDestLon = Float.NaN;
        pendingPickLat = Double.NaN;
        pendingPickLon = Double.NaN;
        if (destinationPickerLayer != null) {
            destinationPickerLayer.hidePin();
        }
        if (binding != null) {
            binding.favoritesPanel.setVisibility(View.GONE);
        }
        // maybeCalculateRoute limpia ruta, tracker, voz y HUD al no haber destino.
        maybeCalculateRoute();
    }

    /** Visibilidad del botón "Cancelar ruta" según haya ruta activa. */
    private void updateCancelRouteButton() {
        if (binding == null) {
            return;
        }
        binding.cancelRouteButton.setVisibility(
                currentRoute != null ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------
    // Sitios guardados (favoritos)
    // ---------------------------------------------------------------------

    /** Corazón relleno si el punto provisional ya está guardado, de contorno si no. */
    private void updateFavoriteToggleIcon() {
        if (binding == null) {
            return;
        }
        boolean saved = !Double.isNaN(pendingPickLat) && !Double.isNaN(pendingPickLon)
                && savedPlacesStore.isSaved(pendingPickLat, pendingPickLon);
        binding.destFavoriteButton.setImageResource(
                saved ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
    }

    /** Guarda o descarta el punto provisional; el panel de destino sigue abierto. */
    private void togglePendingPickFavorite() {
        if (binding == null || Double.isNaN(pendingPickLat) || Double.isNaN(pendingPickLon)) {
            return;
        }
        if (savedPlacesStore.isSaved(pendingPickLat, pendingPickLon)) {
            savedPlacesStore.removeNear(pendingPickLat, pendingPickLon);
        } else {
            savedPlacesStore.add(new SavedPlace(pendingPickLat, pendingPickLon,
                    placeNameFor(pendingPickLat, pendingPickLon)));
        }
        updateFavoriteToggleIcon();
        if (savedPlacesLayer != null) {
            savedPlacesLayer.setPlaces(savedPlacesStore.load());
        }
    }

    /** Nombre de la vía más cercana; si el grafo no la conoce, las coordenadas. */
    @NonNull
    private String placeNameFor(double lat, double lon) {
        String roadName = RoutingManager.getInstance().nearestRoadName(lat, lon);
        if (roadName != null) {
            return roadName;
        }
        return getString(R.string.saved_place_coords, lat, lon);
    }

    private void toggleFavoritesPanel() {
        if (binding == null) {
            return;
        }
        if (binding.favoritesPanel.getVisibility() == View.VISIBLE) {
            binding.favoritesPanel.setVisibility(View.GONE);
            return;
        }
        rebuildFavoritesList();
        binding.favoritesPanel.setVisibility(View.VISIBLE);
    }

    /** Rellena el desplegable con una fila por sitio. Camino frío: solo al abrirlo. */
    private void rebuildFavoritesList() {
        if (binding == null) {
            return;
        }
        binding.favoritesList.removeAllViews();

        List<SavedPlace> places = savedPlacesStore.load();

        if (places.isEmpty()) {
            TextView emptyLabel = new TextView(this);
            emptyLabel.setText(R.string.favorites_empty);
            emptyLabel.setTextSize(20f);
            emptyLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.favoritesList.addView(emptyLabel);
            return;
        }

        boolean isNight = (currentDayNightMode == DayNightMode.NIGHT);
        LayoutInflater inflater = getLayoutInflater();

        for (int i = 0; i < places.size(); i++) {
            final int index = i;
            final SavedPlace place = places.get(i);

            View row = inflater.inflate(R.layout.item_saved_place, binding.favoritesList, false);
            Button placeButton = row.findViewById(R.id.placeButton);
            ImageButton editButton = row.findViewById(R.id.placeEditButton);
            ImageButton deleteButton = row.findViewById(R.id.placeDeleteButton);

            placeButton.setText(place.name);
            ButtonStyler.applySecondary(placeButton, isNight);
            ButtonStyler.applySecondaryIcon(editButton, isNight);
            ButtonStyler.applySecondaryIcon(deleteButton, isNight);

            placeButton.setOnClickListener(v -> routeToSavedPlace(place));
            editButton.setOnClickListener(v -> showFavoriteEditDialog(index, place));
            deleteButton.setOnClickListener(v -> showFavoriteDeleteDialog(index, place));

            binding.favoritesList.addView(row);
        }
    }

    /** Fija el sitio como destino y lanza el cálculo, igual que confirmPickedDestination. */
    private void routeToSavedPlace(@NonNull SavedPlace place) {
        if (binding == null) {
            return;
        }
        prefsManager.setDestination((float) place.lat, (float) place.lon);

        lastCalculatedDestLat = Float.NaN;
        lastCalculatedDestLon = Float.NaN;

        binding.favoritesPanel.setVisibility(View.GONE);
        maybeCalculateRoute();
    }

    /** Diálogo de renombrado de un sitio guardado. */
    private void showFavoriteEditDialog(final int index, @NonNull SavedPlace place) {
        final EditText input = new EditText(this);
        input.setText(place.name);
        input.setTextSize(20f);

        new AlertDialog.Builder(this)
                .setTitle(R.string.favorites_edit_title)
                .setView(input)
                .setPositiveButton(R.string.favorites_edit_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        savedPlacesStore.rename(index, newName);
                    }
                    refreshFavoritesAfterEdit();
                })
                .setNegativeButton(R.string.favorites_edit_cancel, null)
                .show();
    }

    /** Confirmación de borrado; un toque suelto conduciendo no debe perder un sitio. */
    private void showFavoriteDeleteDialog(final int index, @NonNull SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.favorites_delete_title)
                .setMessage(getString(R.string.favorites_delete_message, place.name))
                .setPositiveButton(R.string.favorites_delete_confirm, (dialog, which) -> {
                    savedPlacesStore.removeAt(index);
                    refreshFavoritesAfterEdit();
                })
                .setNegativeButton(R.string.favorites_edit_cancel, null)
                .show();
    }

    private void refreshFavoritesAfterEdit() {
        rebuildFavoritesList();
        if (savedPlacesLayer != null) {
            savedPlacesLayer.setPlaces(savedPlacesStore.load());
        }
    }
}
