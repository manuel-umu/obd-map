package obdmap.launcher.routing;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint3D;

/**
 * Map-matcher con estado: pega la posición a una arista y se adhiere a ella
 * entre fixes, con excepción para las aristas conectadas.
 */
public final class RoadMatcher {

    /** Metros que un candidato no conectado debe mejorar sobre la pegada para contar como evidencia */
    private static final double SWITCH_MARGIN_M = 10.0;

    /** Tiempo con evidencia sobre la misma arista candidata para cambiar de vía */
    private static final long SWITCH_CONFIRM_MS = 1200L;

    /** Distancia máxima a la arista pegada para seguir adherido a ella */
    private static final double KEEP_STICKY_METERS = 25.0;

    /** Tiempo sin match tras el cual se olvida la arista pegada */
    private static final long STICKY_TIMEOUT_MS = 5000L;

    /** Metros por grado de latitud */
    private static final double METERS_PER_DEG = 111320.0;

    /** Desviación angular máxima entre el rumbo GPS y el sentido admitido de la arista */
    private static final float MAX_HEADING_DIFF_DEG = 60.0f;

    /** Fixes consecutivos circulando contra el sentido de la arista pegada antes de soltarla */
    private static final int MAX_WRONG_DIR_FIXES = 2;

    private final HeadingEdgeFilter headingFilter =
            new HeadingEdgeFilter(MAX_HEADING_DIFF_DEG);
    private final double[] projOut = new double[3];

    @Nullable
    private GraphHopper cachedHopper;
    @Nullable
    private Graph graph;
    @Nullable
    private LocationIndex index;
    @Nullable
    private EdgeExplorer explorer;

    private int stickyEdgeId = -1;
    private int stickyBaseNode = -1;
    private int stickyAdjNode = -1;
    private long lastMatchMs = 0L;

    /** Arista candidata acumulando evidencia para relevar a la pegada */
    private int pendingEdgeId = -1;
    private long pendingSinceMs = 0L;

    /** Fixes seguidos en los que la arista pegada no admite el rumbo actual */
    private int wrongDirFixes = 0;

    /**
     * Pega (lat, lon) a la red manteniendo la arista del fix anterior salvo mejora clara.
     * @param out array de tamaño >= 2; out[0]=lat, out[1]=lon del punto pegado
     * @return true si hay match válido y se escribió en out
     */
    public boolean match(@Nullable GraphHopper hopper, double lat, double lon, float bearingDeg,
                         boolean hasBearing, double maxMeters, @NonNull double[] out) {
        if (hopper == null) {
            return false;
        }
        if (hopper != cachedHopper) {
            cachedHopper = hopper;
            graph = hopper.getGraphHopperStorage();
            index = hopper.getLocationIndex();
            explorer = (graph != null) ? graph.createEdgeExplorer() : null;
            headingFilter.setAccessEnc(fetchAccessEnc(hopper));
            forgetSticky();
        }
        if (index == null || graph == null) {
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        if (stickyEdgeId >= 0 && now - lastMatchMs > STICKY_TIMEOUT_MS) {
            forgetSticky();
        }

        // Distancia y proyección sobre la arista pegada
        double stickyDist = Double.MAX_VALUE;
        double stickyLat = 0.0;
        double stickyLon = 0.0;
        if (stickyEdgeId >= 0) {
            EdgeIteratorState sticky = fetchSticky();
            if (sticky == null) {
                forgetSticky();
            } else {
                if (hasBearing) {
                    if (headingFilter.matchesDirection(sticky, lat, lon, bearingDeg)) {
                        wrongDirFixes = 0;
                    } else {
                        wrongDirFixes++;
                    }
                }
                if (wrongDirFixes >= MAX_WRONG_DIR_FIXES) {
                    forgetSticky();
                } else if (projectOnEdge(sticky, lat, lon)) {
                    stickyLat = projOut[0];
                    stickyLon = projOut[1];
                    stickyDist = projOut[2];
                }
            }
        }

        EdgeFilter filter = EdgeFilter.ALL_EDGES;
        if (hasBearing) {
            headingFilter.set(lat, lon, bearingDeg);
            filter = headingFilter;
        }
        QueryResult qr = index.findClosest(lat, lon, filter);
        boolean candidateValid = qr.isValid() && qr.getQueryDistance() <= maxMeters;

        if (stickyEdgeId >= 0 && stickyDist <= KEEP_STICKY_METERS) {
            boolean keepSticky;
            if (!candidateValid) {
                keepSticky = true;
            } else {
                EdgeIteratorState candEdge = qr.getClosestEdge();
                int candId = candEdge.getEdge();
                if (candId == stickyEdgeId) {
                    clearPending();
                    keepSticky = true;
                } else if (isConnectedToSticky(candEdge)) {
                    // Continuación de la misma vía: se acepta sin exigir evidencia
                    clearPending();
                    keepSticky = false;
                } else if (qr.getQueryDistance() <= stickyDist - SWITCH_MARGIN_M) {
                    if (candId != pendingEdgeId) {
                        pendingEdgeId = candId;
                        pendingSinceMs = now;
                    }
                    keepSticky = now - pendingSinceMs < SWITCH_CONFIRM_MS;
                } else {
                    clearPending();
                    keepSticky = true;
                }
            }
            if (keepSticky) {
                out[0] = stickyLat;
                out[1] = stickyLon;
                lastMatchMs = now;
                return true;
            }
        }

        if (!candidateValid) {
            forgetSticky();
            return false;
        }

        EdgeIteratorState candEdge = qr.getClosestEdge();
        stickyEdgeId = candEdge.getEdge();
        stickyBaseNode = candEdge.getBaseNode();
        stickyAdjNode = candEdge.getAdjNode();
        lastMatchMs = now;
        clearPending();

        GHPoint3D snapped = qr.getSnappedPoint();
        out[0] = snapped.lat;
        out[1] = snapped.lon;
        return true;
    }

    /**
     * Proyecta (lat, lon) sobre la arista pegada y sus conectadas, sin tocar el estado.
     * Con rumbo fiable se descartan las conectadas que no admiten ese sentido.
     * @param out array de tamaño >= 2; out[0]=lat, out[1]=lon del punto proyectado
     * @return true si alguna proyección cae dentro de maxMeters y se escribió en out
     */
    public boolean snapAhead(double lat, double lon, float bearingDeg, boolean hasBearing,
                             double maxMeters, @NonNull double[] out) {
        if (stickyEdgeId < 0 || graph == null || explorer == null) {
            return false;
        }
        EdgeIteratorState sticky = fetchSticky();
        if (sticky == null) {
            return false;
        }

        double bestDist = Double.MAX_VALUE;
        double bestLat = 0.0;
        double bestLon = 0.0;

        if (projectOnEdge(sticky, lat, lon)) {
            bestDist = projOut[2];
            bestLat = projOut[0];
            bestLon = projOut[1];
        }

        for (int i = 0; i < 2; i++) {
            int node = (i == 0) ? stickyBaseNode : stickyAdjNode;
            if (node < 0) {
                continue;
            }
            EdgeIterator it = explorer.setBaseNode(node);
            while (it.next()) {
                if (it.getEdge() == stickyEdgeId) {
                    continue;
                }
                if (hasBearing && !headingFilter.matchesDirection(it, lat, lon, bearingDeg)) {
                    continue;
                }
                if (projectOnEdge(it, lat, lon) && projOut[2] < bestDist) {
                    bestDist = projOut[2];
                    bestLat = projOut[0];
                    bestLon = projOut[1];
                }
            }
        }

        if (bestDist > maxMeters) {
            return false;
        }
        out[0] = bestLat;
        out[1] = bestLon;
        return true;
    }

    /**
     * Proyecta (lat, lon) sobre la geometría de la arista
     */
    private boolean projectOnEdge(@NonNull EdgeIteratorState edge, double lat, double lon) {
        PointList geom = edge.fetchWayGeometry(FetchMode.ALL);
        int n = geom.size();
        if (n < 1) {
            return false;
        }
        if (n == 1) {
            double dLat = geom.getLat(0) - lat;
            double dLon = (geom.getLon(0) - lon) * Math.cos(Math.toRadians(lat));
            projOut[0] = geom.getLat(0);
            projOut[1] = geom.getLon(0);
            projOut[2] = Math.sqrt(dLat * dLat + dLon * dLon) * METERS_PER_DEG;
            return true;
        }

        double cosLat = Math.cos(Math.toRadians(lat));
        double px = lon * cosLat;
        double py = lat;

        double bestDistSq = Double.MAX_VALUE;
        double bestLat = lat;
        double bestLon = lon;

        for (int i = 0; i < n - 1; i++) {
            double ax = geom.getLon(i) * cosLat;
            double ay = geom.getLat(i);
            double bx = geom.getLon(i + 1) * cosLat;
            double by = geom.getLat(i + 1);

            double dx = bx - ax;
            double dy = by - ay;
            double lenSq = dx * dx + dy * dy;

            double qx;
            double qy;
            if (lenSq < 1e-18) {
                // Segmento degenerado (vértices duplicados): proyectar al vértice A
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
            double distSq = ddx * ddx + ddy * ddy;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestLat = qy;
                bestLon = (cosLat > 1e-9) ? (qx / cosLat) : lon;
            }
        }
        projOut[0] = bestLat;
        projOut[1] = bestLon;
        projOut[2] = Math.sqrt(bestDistSq) * METERS_PER_DEG;
        return true;
    }

    // Auxiliares

    private void forgetSticky() {
        stickyEdgeId = -1;
        stickyBaseNode = -1;
        stickyAdjNode = -1;
        wrongDirFixes = 0;
        clearPending();
    }

    /**
     * Valor codificado de acceso del encoder "car"; null si el grafo no lo expone
     */
    @Nullable
    private static BooleanEncodedValue fetchAccessEnc(@NonNull GraphHopper hopper) {
        try {
            FlagEncoder encoder = hopper.getEncodingManager().getEncoder("car");
            return encoder.getAccessEnc();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void clearPending() {
        pendingEdgeId = -1;
        pendingSinceMs = 0L;
    }

    @Nullable
    private EdgeIteratorState fetchSticky() {
        Graph g = graph;
        if (g == null) {
            return null;
        }
        try {
            return g.getEdgeIteratorState(stickyEdgeId, Integer.MIN_VALUE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isConnectedToSticky(@NonNull EdgeIteratorState edge) {
        int base = edge.getBaseNode();
        int adj = edge.getAdjNode();
        return base == stickyBaseNode || base == stickyAdjNode
                || adj == stickyBaseNode || adj == stickyAdjNode;
    }
}
