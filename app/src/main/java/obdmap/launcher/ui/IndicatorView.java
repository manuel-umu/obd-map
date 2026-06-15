package obdmap.launcher.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import obdmap.launcher.R;

/**
 * Indicador numérico de una sola magnitud, diseñado para el HUD del mapa.
 *
 * Dibuja tres elementos apilados verticalmente con Canvas:
 *   - Etiqueta (pequeña, text_secondary): qué se está midiendo.
 *   - Valor   (grande, color acento): el número actual.
 *   - Unidad  (pequeña, text_secondary): en qué unidades.
 *
 * Regla de NO allocations en onDraw:
 *   Los tres Paint se crean una sola vez como campos de instancia. En onDraw
 *   no se crea ningún objeto (ni String.format, ni new Rect, ni nada). El
 *   formateo del valor se hace en el setter, fuera del ciclo de dibujo.
 *
 * Dirty-check en lugar de invalidate(Rect):
 *   invalidate(Rect) está deprecado desde API 28 y con aceleración hardware
 *   Android ignora el dirty rect desde API 21 — el framework siempre redibuja
 *   el view completo cuando tiene aceleración hardware activa. Por tanto, la
 *   optimización real es el dirty-check en setValueText(): si el String no
 *   cambió, no se llama a invalidate() en absoluto y el view no se redibuja.
 *   Esto consigue el mismo objetivo (no desperdiciar ciclos de GPU) sin
 *   complejidad adicional.
 *
 * Atributos XML opcionales definidos en res/values/attrs.xml:
 *   app:label="Consumo"    → texto de la etiqueta
 *   app:unit="L/100km"     → texto de la unidad
 */
public final class IndicatorView extends View {

    // Tamaño por defecto en dp; se convierte a píxeles en el constructor.
    private static final int DEFAULT_WIDTH_DP  = 140;
    private static final int DEFAULT_HEIGHT_DP = 90;

    // Tamaños de texto en sp; también se convierten en el constructor.
    private static final int TEXT_SIZE_LABEL_SP = 11;
    private static final int TEXT_SIZE_VALUE_SP = 28;
    private static final int TEXT_SIZE_UNIT_SP  = 11;

    // El valor de "sin dato" que muestra setNoData(). Declarado como constante
    // para compararlo barato en el dirty-check sin crear un String en cada tick.
    private static final String NO_DATA_TEXT = "—";

    // -------------------------------------------------------------------------
    // Paints: creados una vez, nunca en onDraw.
    // -------------------------------------------------------------------------

    /** Paint para la etiqueta y la unidad (texto pequeño, gris secundario). */
    private final Paint labelPaint;

    /** Paint para el valor central (texto grande, color acento). */
    private final Paint valuePaint;

    // -------------------------------------------------------------------------
    // Contenido de los textos.
    // -------------------------------------------------------------------------

    private String labelText = "";
    private String valueText = NO_DATA_TEXT;
    private String unitText  = "";

    // Dimensiones precalculadas al primer onSizeChanged para no recalcular
    // en cada onDraw.
    private float centerX;
    private float centerY;

    // Posiciones Y de cada línea de texto (calculadas en onSizeChanged).
    private float labelY;
    private float valueY;
    private float unitY;

    // =========================================================================
    // Constructores
    // =========================================================================

    public IndicatorView(Context context) {
        this(context, null);
    }

    public IndicatorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IndicatorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // Convertimos los tamaños de texto de sp a px una sola vez aquí.
        float labelSizePx = spToPx(context, TEXT_SIZE_LABEL_SP);
        float valueSizePx = spToPx(context, TEXT_SIZE_VALUE_SP);
        float unitSizePx  = spToPx(context, TEXT_SIZE_UNIT_SP);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(labelSizePx);
        labelPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(valueSizePx);
        valuePaint.setColor(ContextCompat.getColor(context, R.color.accent));
        // El texto de la unidad reutiliza labelPaint: mismas propiedades visuales.

        // Leer atributos XML si los hay (label y unit).
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.IndicatorView);
            try {
                String attrLabel = a.getString(R.styleable.IndicatorView_label);
                String attrUnit  = a.getString(R.styleable.IndicatorView_unit);
                if (attrLabel != null) {
                    labelText = attrLabel;
                }
                if (attrUnit != null) {
                    unitText = attrUnit;
                }
            } finally {
                // Siempre reciclar el TypedArray para evitar fugas de native.
                a.recycle();
            }
        }
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /** Cambia la etiqueta superior. Llama a invalidate() si realmente cambia. */
    public void setLabel(String label) {
        if (label == null) {
            label = "";
        }
        if (!label.equals(labelText)) {
            labelText = label;
            invalidate();
        }
    }

    /** Cambia la unidad inferior. Llama a invalidate() si realmente cambia. */
    public void setUnit(String unit) {
        if (unit == null) {
            unit = "";
        }
        if (!unit.equals(unitText)) {
            unitText = unit;
            invalidate();
        }
    }

    /**
     * Actualiza el texto del valor central.
     *
     * El formateo numérico (String.format, etc.) debe hacerse ANTES de llamar
     * a este método, nunca dentro de onDraw. El dirty-check evita llamadas
     * a invalidate() cuando el texto no ha cambiado, lo que significa que el
     * view no se redibuja innecesariamente entre ticks del listener OBD.
     */
    public void setValueText(String text) {
        if (text == null) {
            text = NO_DATA_TEXT;
        }
        if (!text.equals(valueText)) {
            valueText = text;
            invalidate();
        }
    }

    /** Muestra el símbolo de "sin dato" (—) en el valor central. */
    public void setNoData() {
        setValueText(NO_DATA_TEXT);
    }

    // =========================================================================
    // Medición y posicionado
    // =========================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int defaultW = (int) (DEFAULT_WIDTH_DP  * density + 0.5f);
        int defaultH = (int) (DEFAULT_HEIGHT_DP * density + 0.5f);

        int w = resolveSize(defaultW, widthMeasureSpec);
        int h = resolveSize(defaultH, heightMeasureSpec);

        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        centerX = w / 2.0f;
        centerY = h / 2.0f;

        // Distribuimos el espacio vertical: etiqueta arriba, valor en el centro
        // (desplazado ligeramente hacia arriba del centro real), unidad abajo.
        // Los offsets son empíricos para que quede visual en el tamaño por defecto.
        labelY = centerY - spToPx(getContext(), TEXT_SIZE_VALUE_SP) * 0.65f;
        valueY = centerY + spToPx(getContext(), TEXT_SIZE_VALUE_SP) * 0.35f;
        unitY  = centerY + spToPx(getContext(), TEXT_SIZE_VALUE_SP) * 0.90f
                         + spToPx(getContext(), TEXT_SIZE_UNIT_SP)  * 0.40f;
    }

    // =========================================================================
    // Dibujo — CERO allocations aquí
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        // No dibujamos fondo: el contenedor del layout ya tiene surface_dark semitransparente.

        // Las tres llamadas drawText solo leen campos ya calculados; nada de new.
        canvas.drawText(labelText, centerX, labelY, labelPaint);
        canvas.drawText(valueText, centerX, valueY, valuePaint);
        canvas.drawText(unitText,  centerX, unitY,  labelPaint);
    }

    // =========================================================================
    // Utilidades internas
    // =========================================================================

    /** Convierte sp a píxeles físicos usando la densidad del contexto. */
    private static float spToPx(Context context, int sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp,
                context.getResources().getDisplayMetrics());
    }
}
