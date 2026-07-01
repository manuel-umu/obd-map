package obdmap.launcher.routing;

import androidx.annotation.Nullable;

/**
 * Rastrea el progreso del usuario a lo largo de una ruta calculada.
 * Precomputa las distancias de arco de la polilínea una sola vez al fijar la ruta.
 * Diseñado para llamarse ~1 Hz desde el hilo principal.
 */
public final class NavigationTracker {

    private static final double METERS_PER_DEG = 111320.0;

    // ------------------------------------------------------------------
    // Estado de la ruta activa
    // ------------------------------------------------------------------
    @Nullable
    private Route currentRoute;

    /**
     * arcDist[i] = distancia acumulada en metros desde el vértice 0 hasta el vértice i.
     */
    private double[] arcDist;

    /**
     * instrArcDist[j] = arcDist del vértice de la polilínea más cercano al punto
     * de inicio de la instrucción j.
     */
    private double[] instrArcDist;

    /**
     * Longitudes de cada segmento de la polilínea en metros.
     * Se guarda para ahorrar calculo.
     */
    private double[] segLen;

    // ------------------------------------------------------------------
    // Resultado público del último update()
    // ------------------------------------------------------------------

    /** Índice de la instrucción actual (-1 si no hay ruta). */
    public int currentInstructionIndex = -1;

    /** Sign de la próxima maniobra (constante Instruction.XXX de GraphHopper). */
    public int nextManeuverSign = 0;

    /** Nombre de la calle de la próxima maniobra (vacío si no hay). */
    public String nextManeuverName = "";

    /** Metros hasta la próxima maniobra. */
    public double distanceToManeuverM = 0.0;

    /** Metros hasta el destino final. */
    public double distanceRemainingM = 0.0;

    /** Tiempo estimado restante en milisegundos hasta el destino final. */
    public long timeRemainingMs = 0L;

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    /**
     * Fija la ruta activa y precomputa arcDist[] e instrArcDist[].
     * Pasar null para resetear el tracker (sin ruta activa).
     * Se puede llamar desde cualquier hilo antes de que empiece el ciclo de update().
     *
     * @param route ruta a seguir, o null para desactivar la navegación
     */
    public void setRoute(@Nullable Route route) {
        currentRoute = route;

        if (route == null || route.pointCount() < 2) {
            arcDist = null;
            instrArcDist = null;
            segLen = null;
            resetPublicFields();
            return;
        }

        int n = route.pointCount();
        arcDist = new double[n];
        segLen  = new double[n - 1];

        // Referencia de cosLat: se usa la latitud del primer vértice como aproximación
        // constante. Para rutas de <200 km el error es despreciable.
        double cosLat = Math.cos(Math.toRadians(route.lats[0]));

        // Calcular distancias de arco acumuladas
        arcDist[0] = 0.0;
        for (int i = 0; i < n - 1; i++) {
            double dLat = (route.lats[i + 1] - route.lats[i]) * METERS_PER_DEG;
            double dLon = (route.lons[i + 1] - route.lons[i]) * cosLat * METERS_PER_DEG;
            segLen[i] = Math.sqrt(dLat * dLat + dLon * dLon);
            arcDist[i + 1] = arcDist[i] + segLen[i];
        }

        // Precomputar la distancia de arco de cada instrucción hallando el vértice más cercano
        int instrCount = route.instructionCount();
        instrArcDist = new double[instrCount];
        for (int j = 0; j < instrCount; j++) {
            instrArcDist[j] = arcDist[findNearestVertex(route, route.instrLat[j], route.instrLon[j], cosLat)];
        }

        resetPublicFields();
    }

    /**
     * Actualiza el estado de navegación a partir de la posición GPS.
     * No crea objetos. Escribe directamente en los campos públicos.
     * Llamar ~1 Hz desde el hilo principal mientras hay ruta activa.
     *
     * @param lat latitud del punto GPS (o snapeado a la vía)
     * @param lon longitud del punto GPS (o snapeado a la vía)
     */
    public void update(double lat, double lon) {
        if (currentRoute == null || arcDist == null || instrArcDist == null || segLen == null) {
            resetPublicFields();
            return;
        }

        double currentArcDist = projectOnRoute(lat, lon);

        // Instrucción actual: el índice j más alto tal que instrArcDist[j] <= currentArcDist
        int instrCount = currentRoute.instructionCount();
        int currentIdx = -1;
        for (int j = 0; j < instrCount; j++) {
            if (instrArcDist[j] <= currentArcDist) {
                currentIdx = j;
            }
        }
        currentInstructionIndex = currentIdx;

        int nextIdx = currentIdx + 1;
        if (nextIdx < instrCount) {
            nextManeuverSign = currentRoute.instrSign[nextIdx];
            String name = currentRoute.instrName[nextIdx];
            nextManeuverName = (name != null) ? name : "";
            distanceToManeuverM = Math.max(0.0, instrArcDist[nextIdx] - currentArcDist);
        } else {
            // Ya hemos pasado la última instrucción (destino alcanzado o sin instrucciones)
            nextManeuverSign = 0;
            nextManeuverName = "";
            distanceToManeuverM = 0.0;
        }

        double totalArc = arcDist[arcDist.length - 1];
        distanceRemainingM = Math.max(0.0, totalArc - currentArcDist);

        // Tiempo restante proporcional a la distancia restante sobre la total.
        // Simple y sin crear objetos. Suficientemente preciso para ETA en navegación.
        if (currentRoute.distanceMeters > 0.0 && currentRoute.timeMs > 0L) {
            timeRemainingMs = (long) (currentRoute.timeMs
                    * (distanceRemainingM / currentRoute.distanceMeters));
        } else {
            timeRemainingMs = 0L;
        }
    }

    // ------------------------------------------------------------------
    // Métodos privados de ayuda
    // ------------------------------------------------------------------

    /** Resetea todos los campos públicos a valores neutros. */
    private void resetPublicFields() {
        currentInstructionIndex = -1;
        nextManeuverSign = 0;
        nextManeuverName = "";
        distanceToManeuverM = 0.0;
        distanceRemainingM = 0.0;
        timeRemainingMs = 0L;
    }

    /**
     * Busca el índice del vértice de la polilínea más cercano al punto (lat, lon).
     * Usa distancia euclídea corregida por cosLat.
     *
     * @param route  ruta sobre la que buscar
     * @param lat    latitud del punto de referencia
     * @param lon    longitud del punto de referencia
     * @param cosLat factor de corrección de longitud, precomputado fuera para evitar cos() aquí
     * @return índice del vértice más cercano
     */
    private static int findNearestVertex(Route route, double lat, double lon, double cosLat) {
        double bestDistSq = Double.MAX_VALUE;
        int bestIdx = 0;
        int n = route.pointCount();
        for (int i = 0; i < n; i++) {
            double dLat = (route.lats[i] - lat) * METERS_PER_DEG;
            double dLon = (route.lons[i] - lon) * cosLat * METERS_PER_DEG;
            double distSq = dLat * dLat + dLon * dLon;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Proyecta el punto (lat, lon) sobre la polilínea de la ruta activa y devuelve
     * la distancia de arco en metros desde el origen de la ruta hasta el punto proyectado.
     *
     * Misma aritmética que RoadSnapper para coherencia: coordenadas locales con cosLat.
     * No crea objetos.
     *
     * @return distancia de arco en metros del punto proyectado (>= 0)
     */
    private double projectOnRoute(double lat, double lon) {
        Route route = currentRoute;
        double cosLat = Math.cos(Math.toRadians(lat));

        // Coordenadas locales del punto GPS en espacio métrico aproximado
        double px = lon * cosLat;
        double py = lat;

        double bestDistSq = Double.MAX_VALUE;
        double bestArcDist = 0.0;

        int n = route.pointCount();
        for (int i = 0; i < n - 1; i++) {
            double ax = route.lons[i] * cosLat;
            double ay = route.lats[i];
            double bx = route.lons[i + 1] * cosLat;
            double by = route.lats[i + 1];

            double dx = bx - ax;
            double dy = by - ay;
            double lenSq = dx * dx + dy * dy;

            double t;
            if (lenSq < 1e-18) {
                // Segmento degenerado: proyectar al vértice A
                t = 0.0;
            } else {
                t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
                if (t < 0.0) {
                    t = 0.0;
                } else if (t > 1.0) {
                    t = 1.0;
                }
            }

            // Punto proyectado
            double qx = ax + t * dx;
            double qy = ay + t * dy;

            // Distancia perpendicular en espacio métrico
            double distLat = (qy - py);
            double distLon = (qx - px);
            double distSq = distLat * distLat + distLon * distLon;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                // Distancia de arco del punto proyectado = arcDist del vértice i + fracción del segmento
                bestArcDist = arcDist[i] + t * segLen[i];
            }
        }

        return bestArcDist;
    }
}
