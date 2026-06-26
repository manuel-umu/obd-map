package obdmap.launcher.map;

import androidx.annotation.NonNull;

import org.oscim.android.MapView;
import org.oscim.core.BoundingBox;
import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;
import org.oscim.layers.PathLayer;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.map.Map;
import org.oscim.theme.internal.VtmThemes;
import obdmap.launcher.util.DayNightMode;
import org.oscim.tiling.source.mapfile.MapFileTileSource;
import org.oscim.tiling.source.mapfile.MapInfo;
import org.oscim.layers.tile.vector.VectorTileLayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta y configura el mapa sobre VTM (OpenGL ES 2.0).
 */
public final class MapManager {

    // Auto-zoom por velocidad. Subidos +1 respecto a la vista cenital porque el
    // tilt aleja la imagen: hay que acercar para compensar la perspectiva.
    private static final double CITY_ZOOM         = 18.0;
    private static final double HIGHWAY_ZOOM      = 16.0;
    private static final double CITY_SPEED_KMH    = 50.0;
    private static final double HIGHWAY_SPEED_KMH = 100.0;

    // Suavizado de la velocidad para el zoom (media móvil exponencial).
    // 0.3 = responde en ~3-4 s; amortigua el ruido del GPS que hacía tiritar el zoom.
    private static final float SPEED_SMOOTHING = 0.3f;

    // Inclinación de cámara (perspectiva 3D tipo Waze): 0 = cenital (en picado),
    // ~50 = reclinada para ver la carretera al frente. Máximo de VTM ~65.
    private static final float DRIVE_TILT = 50.0f;

    // Posición vertical del coche en pantalla. 0 = centro; 0.5 = tercio inferior
    // (preset de navegación de VTM, deja más carretera visible por delante).
    private static final float MAP_CENTER_OFFSET_Y = 0.5f;

    // Estilo de la línea de ruta (azul tipo navegador). El ancho está en píxeles.
    private static final int   ROUTE_COLOR = 0xFF1A73E8;
    private static final float ROUTE_WIDTH = 6.0f;

    // Centro de España como fallback si el boundingBox del .map no es válido.
    private static final double FALLBACK_LAT = 40.416775;
    private static final double FALLBACK_LON = -3.703790;

    // Velocidad mínima en m/s para rotar
    private static final float MIN_SPEED_FOR_BEARING_MS = 0.5f;

    // Duración de la animación de posición/rumbo en milisegundos.
    private static final long ANIM_DURATION_MS = 750L;

    private MapView mapView;
    private Map map;
    private MapFileTileSource tileSource;

    // Capa de la polilínea de ruta. Vacía hasta que se calcula una ruta.
    private PathLayer routeLayer;

    // Posición reutilizable para leer el estado actual del mapa — no se pasa al animador.
    private final MapPosition reusablePosition = new MapPosition();

    // Último bearing válido — se mantiene al frenar para que la flecha no baile.
    private float lastBearing = 0f;

    // Velocidad suavizada (m/s) que alimenta el auto-zoom, para que no tirite.
    private float smoothedSpeedMs = 0f;

    /**
     * Engancha el manager al MapView ya inflado, abre el .map y añade las capas.
     * El mapa arranca centrado en el bounding box del archivo.
     */
    public void attachToView(@NonNull MapView view, @NonNull File mapFilePath) {
        this.mapView = view;
        this.map = view.map();

        // Bajar el punto del coche al tercio inferior (estilo navegador): así se
        // ve más carretera por delante. Es config de viewport, se hace una vez.
        map.viewport().setMapViewCenter(0f, MAP_CENTER_OFFSET_Y);

        tileSource = new MapFileTileSource();
        // setMapFile devuelve false si el archivo no existe o está corrupto
        // En ese caso las capas quedan vacías pero no crashea
        tileSource.setMapFile(mapFilePath.getAbsolutePath());

        // Capa base vectorial: lee los tiles del .map y los renderiza via GLES
        VectorTileLayer baseLayer = map.setBaseMap(tileSource);

        // Tema vectorial empaquetado en vtm-themes
        map.setTheme(VtmThemes.DEFAULT);

        // Extrusión 3D de edificios
        map.layers().add(new BuildingLayer(map, baseLayer));

        // Etiquetas de calles y puntos de interes
        map.layers().add(new LabelLayer(map, baseLayer));

        // Capa de ruta, vacía de momento. Se añade ANTES que el marcador del coche
        // (que MainActivity crea después) para que el coche se dibuje por encima.
        routeLayer = new PathLayer(map, ROUTE_COLOR, ROUTE_WIDTH);
        map.layers().add(routeLayer);

        // Centrar en el medio del bounding box del archivo .map.
        MapInfo info = tileSource.getMapInfo();
        double startLat = FALLBACK_LAT;
        double startLon = FALLBACK_LON;

        if (info != null && info.boundingBox != null) {
            BoundingBox bb = info.boundingBox;
            startLat = (bb.getMinLatitude() + bb.getMaxLatitude()) / 2.0;
            startLon = (bb.getMinLongitude() + bb.getMaxLongitude()) / 2.0;
        }

        map.getMapPosition(reusablePosition);
        reusablePosition.setPosition(startLat, startLon);
        reusablePosition.setZoom(CITY_ZOOM);
        map.setMapPosition(reusablePosition);
    }

    /**
     * Actualiza la posición del coche y, si autoCenter está activo, mueve y rota el mapa.
     * Se llama desde el hilo de UI en cada fix GPS (~1 Hz).
     *
     * @param lat        latitud
     * @param lon        longitud
     * @param bearing    rumbo en grados [0, 360)
     * @param hasBearing si el fix GPS trae rumbo válido
     * @param speedMs    velocidad en m/s
     * @param autoCenter si es true, el mapa sigue al coche y rota heading-up
     */
    public void updateCar(double lat, double lon,
                          float bearing, boolean hasBearing, float speedMs,
                          boolean autoCenter) {
        if (map == null) {
            return;
        }

        // Suavizamos la velocidad (media móvil) para que el ruido del GPS no haga
        // tiritar el zoom (acercarse y alejarse a tirones).
        smoothedSpeedMs += SPEED_SMOOTHING * (speedMs - smoothedSpeedMs);

        // Solo actualizamos el bearing cuando el GPS lo confirma y hay velocidad
        // real. Así la flecha no da vueltas aleatoriamente al estar parado.
        if (hasBearing && speedMs >= MIN_SPEED_FOR_BEARING_MS) {
            lastBearing = bearing;
        }

        if (!autoCenter) {
            return;
        }

        map.getMapPosition(reusablePosition);
        float currentMapBearing = reusablePosition.bearing;

        // Calculamos el bearing objetivo según si hay movimiento real o no.
        // Si el coche está parado, mantenemos el bearing actual del mapa
        // para no forzar un giro sin sentido; solo animamos la posición.
        float targetBearing;
        if (hasBearing && speedMs >= MIN_SPEED_FOR_BEARING_MS) {
            // heading-up: el mapa rota para que el rumbo del coche apunte siempre arriba.
            float desiredBearing = -lastBearing;

            // Calculamos la diferencia normalizada a [-180, 180] y la sumamos al
            // bearing actual del mapa
            float delta = desiredBearing - currentMapBearing;
            while (delta > 180f) delta -= 360f;
            while (delta < -180f) delta += 360f;
            targetBearing = currentMapBearing + delta;
        } else {
            // Sin movimiento
            targetBearing = currentMapBearing;
        }

        // Creamos un MapPosition nuevo para el target del animador.
        MapPosition targetPos = new MapPosition();
        targetPos.copy(reusablePosition);
        targetPos.setPosition(lat, lon);
        targetPos.setBearing(targetBearing);
        // Auto-zoom por velocidad (suavizada): alejamos al acelerar (como Waze).
        targetPos.setZoom(zoomForSpeed(smoothedSpeedMs));
        // Inclinación 3D: cámara reclinada para ver la carretera al frente (Waze-style).
        targetPos.setTilt(DRIVE_TILT);

        // animateTo reemplaza cualquier animación en curso
        map.animator().animateTo(ANIM_DURATION_MS, targetPos);
    }

    /**
     * Dibuja la polilínea de la ruta sobre el mapa. Sustituye la ruta anterior
     * si la había. Los puntos llegan como arrays paralelos lat/lon.
     */
    public void showRoute(@NonNull double[] lats, @NonNull double[] lons) {
        if (routeLayer == null || map == null) {
            return;
        }
        int n = Math.min(lats.length, lons.length);
        List<GeoPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(new GeoPoint(lats[i], lons[i]));
        }
        routeLayer.setPoints(points);
        map.updateMap(true);
    }

    // Borra la ruta dibujada (al cancelar destino o calcular otra).
    public void clearRoute() {
        if (routeLayer != null && map != null) {
            routeLayer.clearPath();
            map.updateMap(true);
        }
    }

    /**
     * Centra el mapa en una posición sin cambiar el zoom, animando suavemente.
     * Resetea la rotación a norte arriba para que el usuario se oriente al recentrar.
     * Para el botón "Recentrar" manual.
     */
    public void centerAt(double lat, double lon) {
        if (map == null) {
            return;
        }
        map.getMapPosition(reusablePosition);

        // Mismo razonamiento que en updateCar: objeto nuevo para que el animador
        // no comparta referencia con reusablePosition.
        MapPosition targetPos = new MapPosition();
        targetPos.copy(reusablePosition);
        targetPos.setPosition(lat, lon);
        // Al recentrar manualmente reseteamos la rotación a norte arriba
        targetPos.setBearing(0f);

        map.animator().animateTo(ANIM_DURATION_MS, targetPos);
    }

    /**
     * Zoom según la velocidad: cerca en ciudad, lejos en autopista.
     * Por debajo de CITY_SPEED_KMH devuelve CITY_ZOOM; por encima de
     * HIGHWAY_SPEED_KMH, HIGHWAY_ZOOM; entre medias interpola linealmente.
     */
    private static double zoomForSpeed(float speedMs) {
        double kmh = speedMs * 3.6;
        if (kmh <= CITY_SPEED_KMH) {
            return CITY_ZOOM;
        }
        if (kmh >= HIGHWAY_SPEED_KMH) {
            return HIGHWAY_ZOOM;
        }
        double t = (kmh - CITY_SPEED_KMH) / (HIGHWAY_SPEED_KMH - CITY_SPEED_KMH);
        return CITY_ZOOM + t * (HIGHWAY_ZOOM - CITY_ZOOM);
    }

    /**
     * Cambia el tema del mapa entre día y noche.
     */
    public void applyDayNightTheme(@DayNightMode.Mode int mode) {
        if (map == null) {
            return;
        }
        map.setTheme(mode == DayNightMode.NIGHT ? VtmThemes.TRONRENDER : VtmThemes.DEFAULT);
        map.updateMap(true);
    }

    /** Notifica a VTM que la Activity ha vuelto al frente (reanuda el renderer). */
    public void onResume() {
        if (mapView != null) {
            mapView.onResume();
        }
    }

    /** Notifica a VTM que la Activity va al fondo (pausa el renderer). */
    public void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
    }

    /** Libera el TileSource y las referencias. Llamar desde onDestroy(). */
    public void destroy() {
        if (mapView != null) {
            mapView.onDestroy();
            mapView = null;
        }
        if (tileSource != null) {
            tileSource.close();
            tileSource = null;
        }
        map = null;
    }
}
