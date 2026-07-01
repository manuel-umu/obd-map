package obdmap.launcher.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.core.GeoPoint;
import org.oscim.event.Gesture;
import org.oscim.event.GestureListener;
import org.oscim.event.MotionEvent;
import org.oscim.layers.Layer;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerInterface;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.map.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * Capa VTM que captura long-press sobre el mapa para seleccionar un destino.
 * Al detectar el gesto, obtiene las coordenadas geográficas del punto tocado,
 * pinta un pin provisional y notifica a la Activity mediante el callback.
 *
 * <p>Se añade al mapa después de las capas de ruta y marcador de posición para
 * que aparezca visualmente por encima de ambas.</p>
 */
public final class DestinationPickerLayer extends Layer implements GestureListener {

    /** Tamaño del pin provisional en píxeles lógicos Android. */
    private static final int PIN_SIZE_PX = 48;

    /** Color del pin de destino (azul primario de la app). */
    private static final int PIN_COLOR = 0xFF2196F3;

    /** Callback que recibe el punto elegido, siempre en el hilo de UI. */
    public interface OnDestinationPickedListener {
        /**
         * @param lat latitud del punto tocado
         * @param lon longitud del punto tocado
         */
        void onDestinationPicked(double lat, double lon);
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final OnDestinationPickedListener listener;

    // Capa de marcadores que muestra el pin provisional.
    private final ItemizedLayer markerLayer;
    private final MarkerItem pinItem;

    // Flag que indica si hay un pin visible actualmente.
    private boolean pinVisible = false;

    /**
     * @param map      mapa VTM al que se añade la capa
     * @param listener callback notificado en el hilo de UI cuando el usuario elige un punto
     */
    public DestinationPickerLayer(@NonNull Map map,
                                  @NonNull OnDestinationPickedListener listener) {
        super(map);
        this.listener = listener;

        MarkerSymbol symbol = buildPinSymbol();

        // El pin empieza en (0,0) — se moverá antes de hacerse visible.
        pinItem = new MarkerItem("dest_pin", "", new GeoPoint(0.0, 0.0));
        pinItem.setMarker(symbol);

        List<MarkerInterface> items = new ArrayList<>(1);
        items.add(pinItem);

        // Capa de marcador interna, empieza oculta hasta el primer long-press.
        markerLayer = new ItemizedLayer(map, items, symbol, null);
        markerLayer.setEnabled(false);
        map.layers().add(markerLayer);

        // Esta capa (que implementa GestureListener) también debe estar en el stack
        // de capas del mapa para recibir los gestos de handleGesture().
        // Se añade DESPUÉS del markerLayer para que el GestureListener quede encima.
        map.layers().add(this);
    }

    /**
     * Recibe los gestos del motor de VTM. Solo actúa sobre LONG_PRESS.
     * Devuelve true para consumir el evento y evitar que otras capas lo procesen.
     */
    @Override
    public boolean onGesture(Gesture g, MotionEvent e) {
        if (g != Gesture.LONG_PRESS) {
            return false;
        }

        // fromScreenPoint necesita las coordenadas en píxeles de pantalla.
        // El MotionEvent de VTM expone getX()/getY() con la posición del toque.
        final GeoPoint geoPoint = mMap.viewport().fromScreenPoint(e.getX(), e.getY());

        final double lat = geoPoint.getLatitude();
        final double lon = geoPoint.getLongitude();

        // Mover el pin al punto tocado y hacerlo visible.
        pinItem.geoPoint = new GeoPoint(lat, lon);
        markerLayer.setEnabled(true);
        markerLayer.populate();
        mMap.updateMap(true);
        pinVisible = true;

        // Notificar en el hilo de UI para que la Activity pueda manipular vistas.
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onDestinationPicked(lat, lon);
            }
        });

        return true;
    }

    /** Oculta el pin provisional (al cancelar la selección). */
    public void hidePin() {
        if (!pinVisible) {
            return;
        }
        markerLayer.setEnabled(false);
        mMap.updateMap(true);
        pinVisible = false;
    }

    /**
     * Construye el símbolo del pin: un círculo sólido de color azul.
     * Se rasteriza una sola vez y se reutiliza en todos los frames.
     */
    private static MarkerSymbol buildPinSymbol() {
        Bitmap bmp = Bitmap.createBitmap(PIN_SIZE_PX, PIN_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(PIN_COLOR);

        float radius = PIN_SIZE_PX / 2f;
        canvas.drawCircle(radius, radius, radius, paint);

        // Borde blanco para contraste sobre el mapa.
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(0xFFFFFFFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(3f);
        canvas.drawCircle(radius, radius, radius - 2f, border);

        AndroidBitmap androidBitmap = new AndroidBitmap(bmp);
        // BOTTOM_CENTER: la base del círculo apunta exactamente al GeoPoint.
        return new MarkerSymbol(androidBitmap, MarkerSymbol.HotspotPlace.CENTER, true);
    }
}
