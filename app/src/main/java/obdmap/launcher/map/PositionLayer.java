package obdmap.launcher.map;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.core.GeoPoint;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerInterface;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.map.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * Capa VTM que muestra la flecha del coche como marcador.
 *
 * En modo heading-up el MAPA rota para que el rumbo apunte arriba:
 * la flecha queda siempre centrada y apuntando hacia arriba en pantalla,
 * así que NO la rotamos aquí. Toda la rotación la hace MapManager sobre
 * el MapPosition.
 *
 * El marcador se actualiza en el hilo de UI a ~1 Hz junto con el GPS.
 */
public final class PositionLayer {

    // ItemizedLayer en VTM 0.25.0 no es genérica: trabaja con MarkerInterface.
    private final ItemizedLayer markerLayer;
    private final MarkerItem carMarker;

    /**
     * @param map          mapa VTM al que se añade la capa
     * @param arrowDrawable flecha con la punta hacia arriba (norte); se rasteriza una vez
     */
    public PositionLayer(@NonNull Map map, @NonNull Drawable arrowDrawable) {
        AndroidBitmap bitmap = drawableToBitmap(arrowDrawable);

        // ANCHOR_CENTER: el centro del bitmap cae justo sobre el GeoPoint.
        // billboard = true: el símbolo siempre mira a cámara (ignora la rotación del mapa).
        MarkerSymbol symbol = new MarkerSymbol(bitmap,
                MarkerSymbol.HotspotPlace.CENTER, true);

        // Posición inicial fuera de rango: no se ve hasta el primer fix GPS.
        carMarker = new MarkerItem("car", "", new GeoPoint(0.0, 0.0));
        carMarker.setMarker(symbol);

        // ItemizedLayer requiere List<MarkerInterface>; MarkerItem implementa MarkerInterface.
        List<MarkerInterface> items = new ArrayList<>(1);
        items.add(carMarker);

        markerLayer = new ItemizedLayer(map, items, symbol, null);
        map.layers().add(markerLayer);
    }

    /**
     * Mueve el marcador del coche a la nueva posición GPS.
     * La rotación heading-up la gestiona MapManager; aquí solo actualizamos lat/lon.
     *
     * @param lat latitud
     * @param lon longitud
     */
    public void updatePosition(double lat, double lon) {
        carMarker.geoPoint = new GeoPoint(lat, lon);
        // populate() fuerza el repintado del layer con la nueva posición del marcador.
        markerLayer.populate();
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
