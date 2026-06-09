package obdmap.launcher.map;

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
 * Capa del mapa que dibuja la flecha del coche, girada según el rumbo GPS.
 * Extiende Layer directamente porque el Marker de Mapsforge no sabe rotar.
 *
 * Las rotaciones se cachean en saltos de 5°: girar un bitmap es caro y el GPS
 * actualiza cada segundo, así que cada ángulo se genera una sola vez y se
 * reutiliza. El caché tiene tamaño fijo y no crece nunca.
 */
public final class PositionLayer extends Layer {

    /**
     * Paso del caché de rotaciones: 5° → 72 huecos. Cada bitmap de 48×48
     * ocupa ~9 KB, así que el caché completo no pasa de ~650 KB.
     */
    private static final int BUCKET_DEGREES = 5;
    private static final int CACHE_SIZE = 360 / BUCKET_DEGREES; // 72

    /**
     * Por debajo de esta velocidad el rumbo del GPS es puro ruido:
     * nos quedamos con el último bueno para que la flecha no baile al frenar.
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
     * @param arrowDrawable flecha con la punta hacia arriba (norte);
     *                      se rasteriza a bitmap una sola vez aquí
     */
    public PositionLayer(@NonNull Drawable arrowDrawable) {
        // Convertimos el drawable a android.graphics.Bitmap una única vez.
        // AndroidGraphicFactory.convertToAndroidBitmap ya lo rasteriza al tamaño
        // intrínseco del drawable (48×48 dp del vector).
        this.sourceBitmap = AndroidGraphicFactory.convertToAndroidBitmap(arrowDrawable);
    }

    /**
     * Mueve la flecha a la nueva posición y, si el rumbo es de fiar
     * (el GPS lo da por válido y hay velocidad suficiente), la gira.
     *
     * @param latLong    nueva posición
     * @param bearing    rumbo en grados [0, 360)
     * @param hasBearing si el fix trae rumbo válido
     * @param speedMs    velocidad en m/s, para descartar rumbos de ruido
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

    /** Bitmap girado para ese ángulo; si no está en caché, se crea y se guarda. */
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

    /** Genera el icono girado N grados (0° = punta arriba, sentido horario). */
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

    /** Libera todos los bitmaps (caché y fuente). Llamar desde el hilo de UI. */
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
