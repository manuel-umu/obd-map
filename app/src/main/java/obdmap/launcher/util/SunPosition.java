package obdmap.launcher.util;

/**
 * Altura del sol sobre el horizonte para una posición y un instante.
 * Algoritmo de baja precisión del Astronomical Almanac (error < 1°).
 */
public final class SunPosition {

    /** Altura solar por debajo de la cual se considera de noche (crepúsculo civil). */
    private static final double NIGHT_ALTITUDE_DEG = -6.0;

    /** Días julianos entre el epoch Unix y J2000.0. */
    private static final double DAYS_UNIX_TO_J2000 = 10957.5;

    private static final double MS_PER_DAY = 86_400_000.0;

    private SunPosition() {}

    /** true si el sol está por debajo del crepúsculo civil en ese punto e instante. */
    public static boolean isNight(double latDeg, double lonDeg, long timeMs) {
        return altitudeDeg(latDeg, lonDeg, timeMs) < NIGHT_ALTITUDE_DEG;
    }

    /** Altura del sol en grados: negativa bajo el horizonte, positiva sobre él. */
    public static double altitudeDeg(double latDeg, double lonDeg, long timeMs) {
        double n = timeMs / MS_PER_DAY - DAYS_UNIX_TO_J2000;

        double meanLon      = Math.toRadians(norm360(280.460 + 0.9856474 * n));
        double meanAnomaly  = Math.toRadians(norm360(357.528 + 0.9856003 * n));

        // Longitud eclíptica y oblicuidad.
        double eclipticLon = meanLon
                + Math.toRadians(1.915) * Math.sin(meanAnomaly)
                + Math.toRadians(0.020) * Math.sin(2.0 * meanAnomaly);
        double obliquity = Math.toRadians(23.439 - 0.0000004 * n);

        double declination = Math.asin(Math.sin(obliquity) * Math.sin(eclipticLon));
        double rightAscDeg = Math.toDegrees(Math.atan2(
                Math.cos(obliquity) * Math.sin(eclipticLon), Math.cos(eclipticLon)));

        // Tiempo sidéreo local y ángulo horario del sol.
        double gmstHours = norm24(18.697374558 + 24.06570982441908 * n);
        double localSiderealDeg = norm360(gmstHours * 15.0 + lonDeg);
        double hourAngle = Math.toRadians(norm360(localSiderealDeg - rightAscDeg));

        double lat = Math.toRadians(latDeg);
        double sinAltitude = Math.sin(lat) * Math.sin(declination)
                + Math.cos(lat) * Math.cos(declination) * Math.cos(hourAngle);

        return Math.toDegrees(Math.asin(clamp(sinAltitude)));
    }

    private static double norm360(double deg) {
        double d = deg % 360.0;
        return (d < 0.0) ? d + 360.0 : d;
    }

    private static double norm24(double hours) {
        double h = hours % 24.0;
        return (h < 0.0) ? h + 24.0 : h;
    }

    private static double clamp(double v) {
        if (v > 1.0) {
            return 1.0;
        }
        return Math.max(v, -1.0);
    }
}
