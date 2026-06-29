package obdmap.launcher.map;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;

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

    // Duración mínima y máxima de la interpolación entre fixes GPS
    private static final long MIN_INTERP_MS = 400L;
    private static final long MAX_INTERP_MS = 2000L;

    private final Map vtmMap;
    private final ItemizedLayer markerLayer;
    private final MarkerItem carMarker;

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
     * Actualiza el flag de auto-centrado.
     *
     * @param enabled true si el mapa sigue al coche
     */
    public void setAutoCenter(boolean enabled) {
        autoCenter = enabled;
    }

    /**
     * Callback de {@link Map.UpdateListener}. Se llama en el hilo principal
     * al inicio de cada frame que VTM va a renderizar.
     *
     * <p>Cuando {@code autoCenter=true}: el evento {@link Map#POSITION_EVENT}
     * llega justo DESPUÉS de que el Animator de VTM haya aplicado su interpolación
     * al viewport. El {@code mapPosition} que recibimos YA tiene la posición interpolada
     * del frame actual. Copiamos esa posición al marcador.
     * <p>Cuando {@code autoCenter=false}: calculamos nuestra propia interpolación
     * lineal en función del tiempo transcurrido desde el último fix.
     */
    @Override
    public void onMapEvent(Event e, MapPosition mapPosition) {
        if (Double.isNaN(fromX)) {
            // Sin fix GPS todavía, nada que interpolar.
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

        carMarker.geoPoint = new GeoPoint(markerLat, markerLon);

        // Notifica al MarkerRenderer que los InternalItem.px/py han cambiado.
        markerLayer.populate();
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
