package obdmap.launcher.routing;

import androidx.annotation.Nullable;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

/**
 * Filtro de aristas por rumbo, consciente del sentido de circulación permitido
 */
public final class HeadingEdgeFilter implements EdgeFilter {

    /** Desviación angular máxima entre el rumbo y la dirección de la vía */
    private final float maxHeadingDiffDeg;

    /** Valor codificado de acceso del encoder "car"; null degrada a filtro sin sentido */
    @Nullable
    private BooleanEncodedValue accessEnc;

    private double lat;
    private double lon;
    private float bearingDeg;

    public HeadingEdgeFilter(float maxHeadingDiffDeg) {
        this.maxHeadingDiffDeg = maxHeadingDiffDeg;
    }

    public void setAccessEnc(@Nullable BooleanEncodedValue accessEnc) {
        this.accessEnc = accessEnc;
    }

    public void set(double lat, double lon, float bearingDeg) {
        this.lat = lat;
        this.lon = lon;
        this.bearingDeg = bearingDeg;
    }

    @Override
    public boolean accept(EdgeIteratorState edgeState) {
        return matchesDirection(edgeState, lat, lon, bearingDeg);
    }

    /**
     * Prueba de sentido: si la arista es transitable en el rumbo dado desde (lat, lon)
     */
    public boolean matchesDirection(@Nullable EdgeIteratorState edgeState,
                                    double lat, double lon, float bearingDeg) {
        if (edgeState == null) {
            return false;
        }
        // Geometría real de la arista, en orden base→adj de esta orientación
        PointList geom = edgeState.fetchWayGeometry(FetchMode.ALL);
        int n = geom.size();
        if (n < 2) {
            // Sin dirección evaluable
            return true;
        }

        double cosLat = Math.cos(Math.toRadians(lat));

        int bestSeg = 0;
        double bestDistSq = Double.MAX_VALUE;

        // Segmento de la arista más cercano al punto de consulta
        for (int i = 0; i < n - 1; i++) {
            double distSq = segmentDistSq(lat, lon,
                                          geom.getLat(i), geom.getLon(i),
                                          geom.getLat(i + 1), geom.getLon(i + 1),
                                          cosLat);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestSeg = i;
            }
        }

        float segAz = azimuth(geom.getLat(bestSeg), geom.getLon(bestSeg),
                              geom.getLat(bestSeg + 1), geom.getLon(bestSeg + 1),
                              cosLat);

        float diff = Math.abs(bearingDeg - segAz);
        if (diff > 180f) {
            diff = 360f - diff;
        }

        BooleanEncodedValue enc = accessEnc;
        if (enc == null) {
            // Sin sentido disponible: se pliega en 90 para tolerar geometría invertida
            if (diff > 90f) {
                diff = 180f - diff;
            }
            return diff <= maxHeadingDiffDeg;
        }

        boolean fwd = edgeState.get(enc);
        boolean bwd = edgeState.getReverse(enc);
        if (fwd && bwd) {
            return Math.min(diff, 180f - diff) <= maxHeadingDiffDeg;
        }
        if (fwd) {
            return diff <= maxHeadingDiffDeg;
        }
        if (bwd) {
            return (180f - diff) <= maxHeadingDiffDeg;
        }
        return false;
    }

    /**
     * Distancia al cuadrado (en grados de latitud escalados) del punto de
     * consulta al segmento A-B, con la longitud corregida por cos(lat)
     */
    private static double segmentDistSq(double lat, double lon,
                                        double aLat, double aLon, double bLat, double bLon,
                                        double cosLat) {
        double ax = aLon * cosLat;
        double ay = aLat;
        double bx = bLon * cosLat;
        double by = bLat;
        double px = lon * cosLat;
        double py = lat;

        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;

        double qx;
        double qy;
        if (lenSq < 1e-18) {
            // Segmento degenerado (vertices duplicados): distancia al vertice A
            qx = ax;
            qy = ay;
        } else {
            double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
            qx = ax + t * dx;
            qy = ay + t * dy;
        }

        double ddx = px - qx;
        double ddy = py - qy;
        return ddx * ddx + ddy * ddy;
    }

    private static float azimuth(double aLat, double aLon, double bLat, double bLon, double cosLat) {
        double dLat = bLat - aLat;
        double dLon = (bLon - aLon) * cosLat;
        float az = (float) Math.toDegrees(Math.atan2(dLon, dLat));
        if (az < 0f) {
            az += 360f;
        }
        return az;
    }
}
