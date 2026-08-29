package obdmap.launcher.map;

import android.os.SystemClock;

import org.oscim.core.MapPosition;
import org.oscim.event.Event;
import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.map.Map;

/**
 * LabelLayer que limita la frecuencia de reposicionado de etiquetas.
 */
public final class ThrottledLabelLayer extends LabelLayer {

    /** Tiempo mínimo entre reposicionados de etiquetas. */
    private static final long MIN_RELABEL_MS = 600L;

    private long lastRelabelMs = 0L;

    public ThrottledLabelLayer(Map map, VectorTileLayer baseLayer) {
        super(map, baseLayer);
    }

    @Override
    public void onMapEvent(Event event, MapPosition mapPosition) {
        if (event == Map.POSITION_EVENT && !relabelDue()) {
            return;
        }
        super.onMapEvent(event, mapPosition);
    }

    @Override
    public void onTileManagerEvent(Event event, MapTile tile) {
        if (!relabelDue()) {
            return;
        }
        super.onTileManagerEvent(event, tile);
    }

    private boolean relabelDue() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRelabelMs < MIN_RELABEL_MS) {
            return false;
        }
        lastRelabelMs = now;
        return true;
    }
}
