package com.obdmap.launcher.map;

import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidBitmap;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.layer.Layer;

/**
 * Capa de Mapsforge que dibuja la posición actual del vehículo con un icono
 * rotado según el rumbo GPS. Extiende {@link Layer} directamente porque
 * {@link org.mapsforge.map.layer.overlay.Marker} no expone ningún mecanismo
 * de rotación nativa.
 *
 * <p>La rotación se cachea en bloques de {@value #BUCKET_DEGREES}° para no
 * asignar un nuevo {@link android.graphics.Bitmap} en cada actualización GPS
 * (1 Hz). El array tiene exactamente {@value #CACHE_SIZE} posiciones y nunca
 * crece en tiempo de ejecución.</p>
 */
public final class PositionLayer extends Layer {

    /**
     * Granularidad del caché de rotaciones en grados.
     * 5° → 72 cubos → cada bitmap de 48×48 px ARGB_8888 ≈ 9 KB → total ≤ 648 KB.
     */
    private static final int BUCKET_DEGREES = 5;
    private static final int CACHE_SIZE = 360 / BUCKET_DEGREES; // 72

    /**
     * Umbral de velocidad por debajo del cual el GPS no genera bearing fiable.
     * Por debajo de este valor mantenemos el último bearing conocido.
     */
    private static final float MIN_SPEED_FOR_BEARING_MS = 0.5f;

    // Bitmap fuente (android nativo) del que se generan las rotaciones.
    private final android.graphics.Bitmap sourceBitmap;

    // Caché de bitmaps rotados indexados por bucket (bearing/BUCKET_DEGREES).
    // null == aún no generado para ese ángulo.
    private final Bitmap[] rotatedCache = new Bitmap[CACHE_SIZE];

    // Posición y rumbo actuales. Acceso sólo desde el UI thread.
    @Nullable private LatLong position;
    private float lastBearing = 0f; // último bearing válido

    /**
     * @param arrowDrawable Drawable direccional cuya punta apunta al norte (arriba).
     *                      Se convierte a bitmap una sola vez en el constructor.
     */
    public PositionLayer(@NonNull Drawable arrowDrawable) {
        // Convertimos el drawable a android.graphics.Bitmap una única vez.
        // AndroidGraphicFactory.convertToAndroidBitmap ya lo rasteriza al tamaño
        // intrínseco del drawable (48×48 dp del vector).
        this.sourceBitmap = AndroidGraphicFactory.convertToAndroidBitmap(arrowDrawable);
    }

    /**
     * Actualiza la posición del marcador y su rumbo.
     *
     * @param latLong     Nueva posición geográfica.
     * @param bearing     Rumbo en grados [0, 360). Solo se aplica si {@code hasBearing} es true
     *                    y la velocidad supera el umbral mínimo.
     * @param hasBearing  Indica si el fix GPS incluye un bearing válido.
     * @param speedMs     Velocidad actual en m/s, usada para decidir si el bearing es fiable.
     */
    public void updatePosition(@NonNull LatLong latLong, float bearing,
                               boolean hasBearing, float speedMs) {
        position = latLong;
        // Solo actualizamos el rumbo si el GPS lo reporta como válido y la
        // velocidad es suficiente para que el bearing no sea ruido puro.
        // Si no, mantenemos lastBearing para evitar parpadeo al frenar.
        if (hasBearing && speedMs >= MIN_SPEED_FOR_BEARING_MS) {
            lastBearing = bearing;
        }
        requestRedraw();
    }

    @Override
    public void draw(BoundingBox boundingBox, byte zoomLevel,
                     Canvas canvas, Point topLeftPoint) {
        if (position == null) {
            return;
        }

        int tileSize = displayModel.getTileSize();
        long mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize);

        // Coordenada absoluta del pixel en el espacio del mapa completo.
        double pixelX = MercatorProjection.longitudeToPixelX(position.longitude, mapSize);
        double pixelY = MercatorProjection.latitudeToPixelY(position.latitude, mapSize);

        // Posición relativa al tile superior-izquierdo visible actualmente.
        int screenX = (int) Math.round(pixelX - topLeftPoint.x);
        int screenY = (int) Math.round(pixelY - topLeftPoint.y);

        Bitmap bmp = getRotatedBitmap(lastBearing);

        // Centramos el bitmap sobre el punto de posición.
        int offsetX = -(bmp.getWidth() / 2);
        int offsetY = -(bmp.getHeight() / 2);

        canvas.drawBitmap(bmp, screenX + offsetX, screenY + offsetY);
    }

    /**
     * Devuelve el bitmap rotado correspondiente al ángulo dado, creándolo y
     * cacheándolo si aún no existe para ese bucket.
     */
    private Bitmap getRotatedBitmap(float bearing) {
        // Normalizar bearing a [0, 360)
        float normalized = bearing % 360f;
        if (normalized < 0f) {
            normalized += 360f;
        }

        int bucket = (int) (normalized / BUCKET_DEGREES) % CACHE_SIZE;

        if (rotatedCache[bucket] == null) {
            // Ángulo central del bucket: evita que los extremos del rango varíen
            // de forma visible cuando el bearing oscila justo en el límite.
            float angle = bucket * BUCKET_DEGREES;
            rotatedCache[bucket] = createRotatedBitmap(angle);
        }

        return rotatedCache[bucket];
    }

    /**
     * Crea un nuevo bitmap del icono de posición rotado {@code angleDegrees} grados
     * en sentido horario respecto al norte (punta arriba = 0°).
     */
    private Bitmap createRotatedBitmap(float angleDegrees) {
        Matrix matrix = new Matrix();
        matrix.setRotate(
                angleDegrees,
                sourceBitmap.getWidth() / 2f,
                sourceBitmap.getHeight() / 2f);

        android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                sourceBitmap,
                0, 0,
                sourceBitmap.getWidth(),
                sourceBitmap.getHeight(),
                matrix,
                true /* filter bilinear para suavizar la rotación */);

        // AndroidBitmap(android.graphics.Bitmap) envuelve sin copiar — el bitmap
        // rotado ya es una instancia independiente, así que no hay doble copia.
        AndroidBitmap wrapped = new AndroidBitmap(rotated);
        // Incrementamos la referencia para que Mapsforge no lo libere al
        // reasignar la capa o en gc interno de su pool.
        wrapped.incrementRefCount();
        return wrapped;
    }

    /**
     * Libera todos los bitmaps del caché y el bitmap fuente.
     * Llamar desde el mismo hilo que {@link #onDestroy()} (UI thread).
     */
    @Override
    public void onDestroy() {
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (rotatedCache[i] != null) {
                rotatedCache[i].decrementRefCount();
                rotatedCache[i] = null;
            }
        }
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
        }
        super.onDestroy();
    }
}
