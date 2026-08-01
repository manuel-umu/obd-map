package obdmap.launcher.map;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;
import org.oscim.core.MercatorProjection;
import org.oscim.event.Event;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerInterface;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.map.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * Capa VTM que muestra la flecha del coche como marcador.
 */
public final class PositionLayer implements Map.UpdateListener {

    /** Duración mínima y máxima de la interpolación entre fixes GPS */
    private static final long MIN_INTERP_MS = 400L;
    private static final long MAX_INTERP_MS = 2000L;

    /**
     * Velocidad mínima para hacer caso al rumbo del GPS
     */
    private static final float MIN_SPEED_FOR_BEARING_MS = 0.5f;

    /** Signo de la rotación del símbolo */
    private static final float ROTATION_SIGN = 1f;

    /** Modos de dibujo de la flecha: marcador dentro de VTM o View encima del mapa. */
    private static final int MODE_MARKER = 0;
    private static final int MODE_OVERLAY = 1;

    private final Map vtmMap;
    private final ItemizedLayer markerLayer;
    private final MarkerItem carMarker;

    /** Símbolo del marcador; se le ajusta la rotación frame a frame. */
    private final MarkerSymbol carSymbol;

    /** Último rumbo GPS fiable, en grados [0, 360). */
    private float lastBearingDeg = 0f;
    private boolean hasBearingValue = false;

    // Posición de ORIGEN de la interpolación en curso
    private double fromX = Double.NaN;
    private double fromY = Double.NaN;

    // Posición de DESTINO de la interpolación en curso
    private double toX = 0.0;
    private double toY = 0.0;

    // Instante en que comenzo la interpolacion actual
    private long interpStartMs = 0L;

    // Duración en ms de la interpolación actual
    private long interpDurationMs = 1000L;

    // Instante del último fix GPS para calcular el delta al llegar el siguiente.
    private long lastFixMs = 0L;

    // Flag que controla si el mapa sigue al coche
    private boolean autoCenter = true;

    // Posición intermedia reutilizable para leer el viewport en modo autoCenter=false.
    private final MapPosition reusableViewportPos = new MapPosition();

    // Última posición publicada al marcador, en microgrados (resolución de GeoPoint).
    private int lastPublishedLatE6 = Integer.MIN_VALUE;
    private int lastPublishedLonE6 = Integer.MIN_VALUE;

    // Último ángulo de pantalla publicado al símbolo, en grados enteros [0, 360).
    private int lastPublishedAngle = Integer.MIN_VALUE;

    // Flecha dibujada como View encima del mapa; sustituye al marcador en modo centrado.
    @Nullable
    private View overlayArrow;

    // Modo aplicado a las vistas: -1 sin aplicar, 0 marcador VTM, 1 flecha overlay.
    private int appliedMode = -1;

    /**
     * @param map           mapa VTM al que se añade la capa
     * @param arrowDrawable flecha con la punta hacia arriba (norte); se rasteriza una vez
     */
    public PositionLayer(@NonNull Map map, @NonNull Drawable arrowDrawable) {
        this.vtmMap = map;

        AndroidBitmap bitmap = drawableToBitmap(arrowDrawable);

        // ANCHOR_CENTER: el centro del bitmap cae justo sobre el GeoPoint.
        // billboard=true: el símbolo siempre mira a cámara (ignora la rotación del mapa).
        MarkerSymbol symbol = new MarkerSymbol(bitmap,
                MarkerSymbol.HotspotPlace.CENTER, true);

        carSymbol = symbol;

        // Posición inicial fuera de rango: no se ve hasta el primer fix GPS.
        carMarker = new MarkerItem("car", "", new GeoPoint(0.0, 0.0));
        carMarker.setMarker(symbol);

        List<MarkerInterface> items = new ArrayList<>(1);
        items.add(carMarker);

        markerLayer = new ItemizedLayer(map, items, symbol, null);
        map.layers().add(markerLayer);

        // Registrarse para recibir POSITION_EVENT y UPDATE_EVENT frame a frame.
        map.events.bind(this);
    }

    /**
     * Recibe un nuevo fix GPS. Calcula la duración del siguiente tramo de
     * interpolación como el tiempo transcurrido desde el fix anterior, acotado
     * a un rango sensato para evitar que un fix perdido congele o acelere la flecha
     * @param lat latitud del nuevo fix
     * @param lon longitud del nuevo fix
     */
    public void setTargetPosition(double lat, double lon) {
        long nowMs = SystemClock.elapsedRealtime();

        // Calcular duración del tramo a partir del delta entre fixes consecutivos
        long deltaMs = (lastFixMs == 0L) ? 1000L : (nowMs - lastFixMs);
        lastFixMs = nowMs;

        // Acotar para no acelerar ni congelar la flecha ante fixes irregulares
        if (deltaMs < MIN_INTERP_MS){
            deltaMs = MIN_INTERP_MS;
        }
        if (deltaMs > MAX_INTERP_MS){
            deltaMs = MAX_INTERP_MS;
        }

        // Convertir destino a coordenadas Mercator [0,1] que son lo que usa VTM
        double newToX = MercatorProjection.longitudeToX(lon);
        double newToY = MercatorProjection.latitudeToY(lat);

        // El origen de la nueva interpolación es la posición ACTUALMENTE MOSTRADA.
        // Si ya teníamos una animación en curso, interpolamos desde donde estamos
        // ahora mismo (no desde el fix anterior) para no dar tirones hacia atrás
        if (Double.isNaN(fromX)) {
            // Primer fix: arrancamos desde el propio destino
            fromX = newToX;
            fromY = newToY;
        } else {
            // Leer la posición actualmente mostrada del marcador.
            vtmMap.getMapPosition(reusableViewportPos);
            if (autoCenter) {
                // En modo autoCenter el marcador SIGUE al viewport:
                // la posición visible actual ES el viewport interpolado del frame anterior.
                fromX = reusableViewportPos.x;
                fromY = reusableViewportPos.y;
            } else {
                // En modo manual, calculamos la posición interpolada actual del marcador
                // en función del progreso transcurrido de la animación anterior.
                long elapsed = nowMs - interpStartMs;
                float t = (interpDurationMs > 0) ? ((float) elapsed / interpDurationMs) : 1f;
                if (t > 1f) t = 1f;
                fromX = fromX + (toX - fromX) * t;
                fromY = fromY + (toY - fromY) * t;
            }
        }

        toX = newToX;
        toY = newToY;
        interpStartMs = nowMs;
        interpDurationMs = deltaMs;
    }

    /**
     * Nuevo rumbo del GPS. La flecha se orienta con él en cada frame
     *
     * @param bearingDeg rumbo en grados [0, 360)
     * @param hasBearing true si el fix trae rumbo válido
     * @param speedMs    velocidad en m/s; parados el rumbo no es fiable
     */
    public void setBearing(float bearingDeg, boolean hasBearing, float speedMs) {
        if (hasBearing && speedMs >= MIN_SPEED_FOR_BEARING_MS) {
            lastBearingDeg  = bearingDeg;
            hasBearingValue = true;
        }
    }

    /**
     * Actualiza el flag de auto-centrado.
     *
     * @param enabled true si el mapa sigue al coche
     */
    public void setAutoCenter(boolean enabled) {
        autoCenter = enabled;
    }

    /**
     * Fija la View que hace de flecha en modo centrado.
     *
     * @param arrow ImageView del layout, o null para volver siempre al marcador VTM
     */
    public void setOverlayArrow(@Nullable View arrow) {
        overlayArrow = arrow;
    }

    /**
     * Callback de {@link Map.UpdateListener}, una vez por frame en el hilo principal.
     *
     * <p>Con {@code autoCenter=true} el coche cae siempre en el mismo punto de la
     * pantalla, así que solo se rota la flecha overlay: ni marcador ni populate().
     * <p>Con {@code autoCenter=false} el marcador VTM interpola su propia posición
     * en función del tiempo transcurrido desde el último fix.
     */
    @Override
    public void onMapEvent(Event e, MapPosition mapPosition) {
        if (Double.isNaN(fromX)) {
            // Sin fix GPS todavía, nada que interpolar.
            return;
        }

        int mode = (autoCenter && overlayArrow != null) ? MODE_OVERLAY : MODE_MARKER;
        if (mode != appliedMode) {
            appliedMode = mode;
            applyMode(mode);
        }

        if (mode == MODE_OVERLAY) {
            updateOverlayArrow(mapPosition);
            return;
        }

        double markerLat;
        double markerLon;

        if (autoCenter) {
            // Ya se interpoló el viewport a la posición del coche.
            // Leer directamente del MapPosition del evento
            markerLat = mapPosition.getLatitude();
            markerLon = mapPosition.getLongitude();
        } else {
            long elapsed = SystemClock.elapsedRealtime() - interpStartMs;
            float t = (interpDurationMs > 0) ? ((float) elapsed / interpDurationMs) : 1f;
            if (t > 1f) t = 1f;

            double interpX = fromX + (toX - fromX) * t;
            double interpY = fromY + (toY - fromY) * t;

            markerLat = org.oscim.core.MercatorProjection.toLatitude(interpY);
            markerLon = org.oscim.core.MercatorProjection.toLongitude(interpX);
        }

        // Microgrados: la resolución real a la que GeoPoint guarda la posición.
        int latE6 = (int) (markerLat * 1E6);
        int lonE6 = (int) (markerLon * 1E6);

        // Orientación de la flecha en pantalla, redondeada a grados enteros.
        int screenAngleDeg = lastPublishedAngle;
        if (hasBearingValue) {
            screenAngleDeg = screenAngleFor(mapPosition);
        }

        boolean positionChanged = latE6 != lastPublishedLatE6 || lonE6 != lastPublishedLonE6;
        boolean angleChanged = screenAngleDeg != lastPublishedAngle;

        // Sin cambios a la resolución dibujable: no marcar el renderer sucio.
        if (!positionChanged && !angleChanged) {
            return;
        }

        if (positionChanged) {
            carMarker.geoPoint = new GeoPoint(markerLat, markerLon);
            lastPublishedLatE6 = latE6;
            lastPublishedLonE6 = lonE6;
        }

        if (angleChanged) {
            carSymbol.setRotation(ROTATION_SIGN * screenAngleDeg);
            lastPublishedAngle = screenAngleDeg;
        }

        // Notifica al MarkerRenderer que los InternalItem.px/py han cambiado.
        markerLayer.populate();
    }

    /** Conmuta marcador VTM y flecha overlay. Solo en la transición de modo. */
    private void applyMode(int mode) {
        boolean overlay = (mode == MODE_OVERLAY);
        markerLayer.setEnabled(!overlay);
        if (overlayArrow != null) {
            overlayArrow.setVisibility(overlay ? View.VISIBLE : View.GONE);
        }
        // El destino de la posición y del ángulo cambia: los valores publicados
        // pertenecen al modo anterior y hay que reenviarlos al nuevo.
        lastPublishedLatE6 = Integer.MIN_VALUE;
        lastPublishedLonE6 = Integer.MIN_VALUE;
        lastPublishedAngle = Integer.MIN_VALUE;
    }

    /** Rota la View de la flecha; no reserva objetos ni toca VTM. */
    private void updateOverlayArrow(MapPosition mapPosition) {
        View arrow = overlayArrow;
        if (arrow == null || !hasBearingValue) {
            return;
        }
        int screenAngleDeg = screenAngleFor(mapPosition);
        if (screenAngleDeg == lastPublishedAngle) {
            return;
        }
        lastPublishedAngle = screenAngleDeg;
        arrow.setRotation(ROTATION_SIGN * screenAngleDeg);
    }

    /** Ángulo de la flecha en pantalla, en grados enteros [0, 360). */
    private int screenAngleFor(MapPosition mapPosition) {
        float screenAngle = lastBearingDeg + mapPosition.bearing;
        screenAngle %= 360f;
        if (screenAngle < 0f) {
            screenAngle += 360f;
        }
        return Math.round(screenAngle) % 360;
    }

    public void detach() {
        vtmMap.events.unbind(this);
    }

    /** Rasteriza un Drawable a AndroidBitmap usando su tamaño intrínseco. */
    private static AndroidBitmap drawableToBitmap(@NonNull Drawable drawable) {
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();

        // Fallback si el drawable no tiene tamaño intrínseco (drawable sólido, etc.).
        if (w <= 0) w = 48;
        if (h <= 0) h = 48;

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);

        return new AndroidBitmap(bmp);
    }
}
