package obdmap.launcher.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
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

import obdmap.launcher.prefs.SavedPlace;

/**
 * Capa VTM con un marcador de corazón por sitio guardado.
 */
public final class SavedPlacesLayer {
    private static final int HEART_SIZE_PX = 40;
    private static final int HEART_COLOR = 0xFFFFB300;
    private final Map vtmMap;
    private final ItemizedLayer markerLayer;
    private final MarkerSymbol heartSymbol;

    public SavedPlacesLayer(@NonNull Map map, @NonNull Drawable heartDrawable) {
        this.vtmMap = map;
        this.heartSymbol = buildHeartSymbol(heartDrawable);

        List<MarkerInterface> items = new ArrayList<>();
        markerLayer = new ItemizedLayer(map, items, heartSymbol, null);
        map.layers().add(markerLayer);
    }

    /**
     * Reconstruye los marcadores.
     */
    public void setPlaces(@NonNull List<SavedPlace> places) {
        List<MarkerInterface> items = new ArrayList<>(places.size());
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            MarkerItem item = new MarkerItem("", "", new GeoPoint(place.lat, place.lon));
            item.setMarker(heartSymbol);
            items.add(item);
        }

        markerLayer.removeAllItems(false);
        // addItems ya invoca populate() una única vez.
        markerLayer.addItems(items);
        vtmMap.updateMap(true);
    }

    private static MarkerSymbol buildHeartSymbol(@NonNull Drawable drawable) {
        Drawable tinted = drawable.mutate();
        tinted.setColorFilter(HEART_COLOR, PorterDuff.Mode.SRC_IN);

        Bitmap bmp = Bitmap.createBitmap(HEART_SIZE_PX, HEART_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        tinted.setBounds(0, 0, HEART_SIZE_PX, HEART_SIZE_PX);
        tinted.draw(canvas);

        return new MarkerSymbol(new AndroidBitmap(bmp),
                MarkerSymbol.HotspotPlace.CENTER, true);
    }
}
