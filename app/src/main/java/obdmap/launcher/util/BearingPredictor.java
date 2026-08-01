package obdmap.launcher.util;

/**
 * Estimador de la velocidad angular de giro y extrapolador del rumbo.
 */
public final class BearingPredictor {

    /** Milisegundos de rumbo que se extrapolan hacia delante. */
    public static final long BEARING_LOOKAHEAD_MS = 1000L;

    /** Tope del término de extrapolación, en grados. */
    public static final float MAX_BEARING_LEAD_DEG = 45.0f;

    /** Factor de la media móvil exponencial de la velocidad angular. */
    public static final float TURN_RATE_SMOOTHING = 0.5f;

    private float prevBearingDeg;
    private long prevTimeMs;
    private boolean hasPrev = false;

    // Velocidad angular suavizada en grados/segundo, con signo.
    private float turnRateDegS = 0.0f;

    public void update(float bearingDeg, boolean hasBearing, long nowMs) {
        if (!hasBearing) {
            return;
        }
        if (!hasPrev) {
            prevBearingDeg = bearingDeg;
            prevTimeMs = nowMs;
            hasPrev = true;
            turnRateDegS = 0.0f;
            return;
        }

        long dtMs = nowMs - prevTimeMs;
        float diff = bearingDeg - prevBearingDeg;
        prevBearingDeg = bearingDeg;
        prevTimeMs = nowMs;
        if (dtMs <= 0L) {
            return;
        }

        while (diff > 180.0f) {
            diff -= 360.0f;
        }
        while (diff < -180.0f) {
            diff += 360.0f;
        }

        float rate = diff / (dtMs / 1000.0f);
        turnRateDegS = turnRateDegS + TURN_RATE_SMOOTHING * (rate - turnRateDegS);
    }

    /**
     * Rumbo extrapolado {@link #BEARING_LOOKAHEAD_MS} hacia delante,
     * normalizado a [0, 360).
     */
    public float predictBearing(float bearingDeg) {
        float lead = turnRateDegS * (BEARING_LOOKAHEAD_MS / 1000.0f);
        if (lead > MAX_BEARING_LEAD_DEG) {
            lead = MAX_BEARING_LEAD_DEG;
        } else if (lead < -MAX_BEARING_LEAD_DEG) {
            lead = -MAX_BEARING_LEAD_DEG;
        }

        float result = bearingDeg + lead;
        while (result >= 360.0f) {
            result -= 360.0f;
        }
        while (result < 0.0f) {
            result += 360.0f;
        }
        return result;
    }

    public float getTurnRateDegS() {
        return turnRateDegS;
    }
}
