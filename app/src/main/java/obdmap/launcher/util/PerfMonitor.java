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
    private int mapEventCount = 0;

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
        mapEventCount = 0;
    }

    public void countGpsFix() {
        if (running) {
            gpsFixCount++;
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

        long gcCount = readStat(STAT_GC_COUNT);
        long gcTime = readStat(STAT_GC_TIME);
        long blockingCount = readStat(STAT_BLOCKING_GC_COUNT);
        long blockingTime = readStat(STAT_BLOCKING_GC_TIME);
        long heapUsed = usedHeap();

        report.setLength(0);

        report.append("fps ").append(perSecond(frameCount, elapsedMs))
                .append("  peor ").append(millisOf(worstFrameNanos)).append("ms\n");

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

        report.append("gps ").append(perSecond(gpsFixCount, elapsedMs))
                .append("/s  mapa ").append(perSecond(mapEventCount, elapsedMs)).append("/s");

        beginWindow(nowMs, heapUsed, gcCount, gcTime, blockingCount, blockingTime);
        return report.toString();
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
        mapEventCount = 0;
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
