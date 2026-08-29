package obdmap.launcher.util;

import android.os.Debug;
import android.os.SystemClock;
import android.view.Choreographer;

import androidx.annotation.Nullable;

/**
 * Muestreo de rendimiento en ventanas de 1 s: FPS, peor frame, GC, heap y
 * contadores de la app.
 */
public final class PerfMonitor implements Choreographer.FrameCallback {

    // Fases cronometradas dentro de un fix de GPS.
    public static final int PHASE_SNAP = 0;
    public static final int PHASE_MATCH = 1;
    public static final int PHASE_PREDICT = 2;
    public static final int PHASE_NAV = 3;
    public static final int PHASE_HUD = 4;
    public static final int PHASE_PREFS = 5;
    public static final int PHASE_FIX_TOTAL = 6;
    public static final int PHASE_COUNT = 7;

    private static final String[] PHASE_LABELS = {
            "snap", "match", "pred", "nav", "hud", "prefs", "fix"
    };

    // Umbral por debajo del cual una fase no se imprime.
    private static final long PHASE_REPORT_MS = 20L;

    // Hueco entre frames a partir del cual se considera bloqueo del hilo principal.
    private static final long STALL_THRESHOLD_MS = 200L;

    // Duración de la ventana de muestreo.
    private static final long WINDOW_MS = 1000L;
    private static final String STAT_GC_COUNT = "art.gc.gc-count";
    private static final String STAT_GC_TIME = "art.gc.gc-time";
    private static final String STAT_BLOCKING_GC_COUNT = "art.gc.blocking-gc-count";
    private static final String STAT_BLOCKING_GC_TIME = "art.gc.blocking-gc-time";
    private static final long STAT_UNAVAILABLE = -1L;

    // Buffer del informe; una única construcción de String por ventana.
    private final StringBuilder report = new StringBuilder(160);
    public interface ReportListener {
        void onPerfReport(String report);
    }
    @Nullable
    private ReportListener reportListener;

    @Nullable
    private Choreographer choreographer;

    private boolean running = false;

    // Instante de inicio de la ventana en curso.
    private long windowStartMs = 0L;
    private int frameCount = 0;
    private long worstFrameNanos = 0L;

    // Marca del frame anterior; 0 mientras no haya con qué comparar.
    private long lastFrameNanos = 0L;

    private int gpsFixCount = 0;
    private int gpsFixDoneCount = 0;
    private int mapEventCount = 0;

    // false = solo la línea de fps; true = resto
    private boolean fullReport = false;

    // Peor duración de cada fase en la ventana en curso, en ms.
    private final long[] worstPhaseMs = new long[PHASE_COUNT];

    // Último bloqueo detectado; sobrevive a los cambios de ventana.
    private long lastStallMs = 0L;
    private long lastStallAtMs = 0L;

    // Línea base de la ventana: contadores de ART y heap Java usado en bytes.
    private long baseGcCount = STAT_UNAVAILABLE;
    private long baseGcTime = STAT_UNAVAILABLE;
    private long baseBlockingCount = STAT_UNAVAILABLE;
    private long baseBlockingTime = STAT_UNAVAILABLE;
    private long baseHeapUsed = 0L;

    /** Registra el FrameCallback y abre la primera ventana. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastFrameNanos = 0L;
        choreographer = Choreographer.getInstance();
        beginWindow(SystemClock.elapsedRealtime(), usedHeap(),
                readStat(STAT_GC_COUNT), readStat(STAT_GC_TIME),
                readStat(STAT_BLOCKING_GC_COUNT), readStat(STAT_BLOCKING_GC_TIME));
        choreographer.postFrameCallback(this);
    }

    /** Quita el FrameCallback: apagado no queda trabajo por frame. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (choreographer != null) {
            choreographer.removeFrameCallback(this);
        }
        frameCount = 0;
        worstFrameNanos = 0L;
        gpsFixCount = 0;
        gpsFixDoneCount = 0;
        mapEventCount = 0;
        lastStallMs = 0L;
        lastStallAtMs = 0L;
    }

    public void countGpsFix() {
        if (running) {
            gpsFixCount++;
        }
    }

    public void countGpsFixDone() {
        if (running) {
            gpsFixDoneCount++;
        }
    }

    /** Detalle del informe: solo fps, o todas las métricas. */
    public void setFullReport(boolean full) {
        fullReport = full;
    }

    /** Marca de inicio de fase, o 0 si el monitor está parado. */
    public long phaseStart() {
        return running ? SystemClock.elapsedRealtime() : 0L;
    }

    /** Guarda la duración de la fase si es la peor de la ventana. */
    public void phaseEnd(int phase, long startMs) {
        if (!running) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - startMs;
        if (elapsed > worstPhaseMs[phase]) {
            worstPhaseMs[phase] = elapsed;
        }
    }

    public void countMapEvent() {
        if (running) {
            mapEventCount++;
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        if (lastFrameNanos != 0L) {
            long deltaNanos = frameTimeNanos - lastFrameNanos;
            if (deltaNanos > worstFrameNanos) {
                worstFrameNanos = deltaNanos;
            }
            long deltaMs = deltaNanos / 1000000L;
            if (deltaMs >= STALL_THRESHOLD_MS) {
                lastStallMs = deltaMs;
                lastStallAtMs = SystemClock.elapsedRealtime();
            }
        }
        lastFrameNanos = frameTimeNanos;
        frameCount++;

        // El informe se emite desde aquí: así el panel sigue vivo aunque el GPS se atasque.
        ReportListener listener = reportListener;
        if (listener != null) {
            String pending = consumeReport();
            if (pending != null) {
                listener.onPerfReport(pending);
            }
        }

        Choreographer c = choreographer;
        if (c != null) {
            c.postFrameCallback(this);
        }
    }

    /**
     * Informe de la ventana recién cerrada, o null si aún no ha pasado el segundo
     * o el monitor está parado.
     */
    @Nullable
    public String consumeReport() {
        if (!running) {
            return null;
        }
        long nowMs = SystemClock.elapsedRealtime();
        long elapsedMs = nowMs - windowStartMs;
        if (elapsedMs < WINDOW_MS) {
            return null;
        }

        report.setLength(0);
        report.append("fps ").append(perSecond(frameCount, elapsedMs))
                .append("  peor ").append(millisOf(worstFrameNanos)).append("ms");

        if (!fullReport) {
            beginWindow(nowMs, baseHeapUsed, baseGcCount, baseGcTime,
                    baseBlockingCount, baseBlockingTime);
            return report.toString();
        }
        report.append('\n');

        long gcCount = readStat(STAT_GC_COUNT);
        long gcTime = readStat(STAT_GC_TIME);
        long blockingCount = readStat(STAT_BLOCKING_GC_COUNT);
        long blockingTime = readStat(STAT_BLOCKING_GC_TIME);
        long heapUsed = usedHeap();

        appendGcLine("gc", gcCount, gcTime, baseGcCount, baseGcTime);
        appendGcLine("bloq", blockingCount, blockingTime, baseBlockingCount, baseBlockingTime);

        report.append("heap ").append(heapUsed >> 20)
                .append('/').append(Runtime.getRuntime().maxMemory() >> 20).append("MB  ");
        long growthKb = (heapUsed - baseHeapUsed) / 1024L;
        if (growthKb >= 0L) {
            report.append('+');
        }
        report.append(growthKb).append("KB/s\n");

        report.append("nativo ").append(Debug.getNativeHeapAllocatedSize() >> 20).append("MB\n");

        appendSlowPhases();

        if (lastStallAtMs != 0L) {
            report.append("bloqueo ").append(lastStallMs).append("ms hace ")
                    .append((nowMs - lastStallAtMs) / 1000L).append("s\n");
        }

        report.append("gps ").append(perSecond(gpsFixCount, elapsedMs))
                .append('/').append(perSecond(gpsFixDoneCount, elapsedMs))
                .append("  mapa ").append(perSecond(mapEventCount, elapsedMs)).append("/s");


        beginWindow(nowMs, heapUsed, gcCount, gcTime, blockingCount, blockingTime);
        return report.toString();
    }

    /** Línea con las fases que pasaron del umbral en la ventana. */
    private void appendSlowPhases() {
        report.append("lento:");
        boolean any = false;
        for (int i = 0; i < PHASE_COUNT; i++) {
            if (worstPhaseMs[i] > PHASE_REPORT_MS) {
                report.append(' ').append(PHASE_LABELS[i]).append(' ')
                        .append(worstPhaseMs[i]).append("ms ");
                any = true;
            }
        }
        if (!any) {
            report.append(" -");
        }
        report.append('\n');
    }

    /** Línea "delta de la ventana + acumulado" de un par de contadores de GC. */
    private void appendGcLine(String label, long count, long timeMs,
                              long baseCount, long baseTimeMs) {
        report.append(label).append(' ');
        if (count == STAT_UNAVAILABLE || timeMs == STAT_UNAVAILABLE) {
            report.append("--\n");
            return;
        }
        long deltaCount = (baseCount == STAT_UNAVAILABLE) ? 0L : count - baseCount;
        long deltaTime = (baseTimeMs == STAT_UNAVAILABLE) ? 0L : timeMs - baseTimeMs;
        report.append('+').append(deltaCount).append(' ').append(deltaTime).append("ms")
                .append("  tot ").append(count).append(' ').append(timeMs).append("ms\n");
    }

    private void beginWindow(long nowMs, long heapUsed, long gcCount, long gcTime,
                             long blockingCount, long blockingTime) {
        windowStartMs = nowMs;
        frameCount = 0;
        worstFrameNanos = 0L;
        gpsFixCount = 0;
        gpsFixDoneCount = 0;
        mapEventCount = 0;
        for (int i = 0; i < PHASE_COUNT; i++) {
            worstPhaseMs[i] = 0L;
        }
        baseHeapUsed = heapUsed;
        baseGcCount = gcCount;
        baseGcTime = gcTime;
        baseBlockingCount = blockingCount;
        baseBlockingTime = blockingTime;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static int perSecond(int count, long elapsedMs) {
        return (int) (count * 1000L / elapsedMs);
    }

    private static int millisOf(long nanos) {
        return (int) ((nanos + 500000L) / 1000000L);
    }

    /** Contador de ART, o STAT_UNAVAILABLE si el dispositivo no lo publica. */
    private static long readStat(String key) {
        String value = Debug.getRuntimeStat(key);
        if (value == null) {
            return STAT_UNAVAILABLE;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return STAT_UNAVAILABLE;
        }
    }

    public void setReportListener(@Nullable ReportListener listener) {
        this.reportListener = listener;
    }
}
