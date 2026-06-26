package obdmap.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Badge circular que muestra la velocidad GPS actual en km/h.
 */
public final class SpeedBadgeView extends View {

    // Tamaño por defecto
    private static final int DEFAULT_SIZE_DP = 90;
    private static final int TEXT_SIZE_VALUE_SP = 32;
    private static final int TEXT_SIZE_UNIT_SP  = 12;
    private static final String UNIT_KMH   = "km/h";
    private static final String NO_DATA_TEXT = "--";

    // Colores modo noche
    private static final int COLOR_NIGHT_BACKGROUND = 0xCC1B2026; // surface_dark + alpha CC
    private static final int COLOR_NIGHT_VALUE      = 0xFFFFB300; // accent
    private static final int COLOR_NIGHT_UNIT       = 0xFFB0B0B0; // text_secondary

    // Colores modo día
    private static final int COLOR_DAY_BACKGROUND  = 0xCCFFFFFF; // blanco + alpha CC
    private static final int COLOR_DAY_VALUE       = 0xFF212121; // text_primary_day
    private static final int COLOR_DAY_UNIT        = 0xFF757575; // text_secondary_day

    // -------------------------------------------------------------------------
    // Paints
    // -------------------------------------------------------------------------
    private final Paint circlePaint;
    private final Paint valuePaint;
    private final Paint unitPaint;

    // -------------------------------------------------------------------------
    // Geometría
    // -------------------------------------------------------------------------
    private float centerX;
    private float centerY;
    private float radius;
    private float valueY;
    private float unitY;

    // -------------------------------------------------------------------------
    // Estado del texto
    // -------------------------------------------------------------------------
    private String valueText = NO_DATA_TEXT;

    // =========================================================================
    // Constructores
    // =========================================================================
    public SpeedBadgeView(Context context) {
        this(context, null);
    }

    public SpeedBadgeView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpeedBadgeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float valueSizePx = spToPx(context, TEXT_SIZE_VALUE_SP);
        float unitSizePx  = spToPx(context, TEXT_SIZE_UNIT_SP);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(COLOR_NIGHT_BACKGROUND);

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(valueSizePx);
        valuePaint.setColor(COLOR_NIGHT_VALUE);

        unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTextSize(unitSizePx);
        unitPaint.setColor(COLOR_NIGHT_UNIT);
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Actualiza la velocidad mostrada.
     *
     * Convierte m/s a km/h con redondeo y dirty-check
     *
     * @param speedMs velocidad del GPS en metros por segundo.
     */
    public void setSpeed(float speedMs) {
        int kmh = Math.round(speedMs * 3.6f);
        // Integer.toString reutiliza el pool de strings pequeños para 0-127.
        String newText = Integer.toString(kmh);
        if (!newText.equals(valueText)) {
            valueText = newText;
            invalidate();
        }
    }

    /** Muestra "--" cuando no hay fix GPS o el proveedor está deshabilitado. */
    public void setNoData() {
        if (!NO_DATA_TEXT.equals(valueText)) {
            valueText = NO_DATA_TEXT;
            invalidate();
        }
    }

    /**
     * Cambia la paleta de colores según el modo día/noche.
     */
    public void applyNightMode(boolean isNight) {
        if (isNight) {
            circlePaint.setColor(COLOR_NIGHT_BACKGROUND);
            valuePaint.setColor(COLOR_NIGHT_VALUE);
            unitPaint.setColor(COLOR_NIGHT_UNIT);
        } else {
            circlePaint.setColor(COLOR_DAY_BACKGROUND);
            valuePaint.setColor(COLOR_DAY_VALUE);
            unitPaint.setColor(COLOR_DAY_UNIT);
        }
        invalidate();
    }

    // =========================================================================
    // Medición y posicionado
    // =========================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int defaultSize = (int) (DEFAULT_SIZE_DP * density + 0.5f);

        // El view siempre es cuadrado: ancho == alto == defaultSize
        int side = resolveSize(defaultSize, widthMeasureSpec);
        side = Math.min(side, resolveSize(defaultSize, heightMeasureSpec));
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        centerX = w / 2.0f;
        centerY = h / 2.0f;
        // Radio con un pequeño margen para que el círculo no llegue al borde
        radius = Math.min(w, h) / 2.0f - 2.0f;

        float valueSizePx = valuePaint.getTextSize();
        float unitSizePx  = unitPaint.getTextSize();

        // El número grande queda ligeramente por encima del centro;
        // la unidad va justo debajo con un pequeño espacio.
        valueY = centerY + valueSizePx * 0.35f;
        unitY  = valueY  + unitSizePx  * 1.20f;
    }

    // =========================================================================
    // Dibujo
    // =========================================================================
    @Override
    protected void onDraw(Canvas canvas) {
        // Círculo de fondo semitransparente
        canvas.drawCircle(centerX, centerY, radius, circlePaint);
        // Número de velocidad centrado
        canvas.drawText(valueText, centerX, valueY, valuePaint);
        // Etiqueta de unidad
        canvas.drawText(UNIT_KMH, centerX, unitY, unitPaint);
    }

    // =========================================================================
    // Utils
    // =========================================================================
    private static float spToPx(Context context, int sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp,
                context.getResources().getDisplayMetrics());
    }
}
