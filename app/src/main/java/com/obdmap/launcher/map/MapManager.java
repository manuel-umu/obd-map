package com.obdmap.launcher.map;

import android.content.Context;

import androidx.annotation.NonNull;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;

import java.io.File;

/**
 * Encapsula la configuración del {@link MapView} de Mapsforge: caché de tiles,
 * lector del archivo .map y capa de renderizado. La instancia es propietaria
 * del cache y del MapFile, por lo que debe llamarse a {@link #destroy()} en el
 * onDestroy de la Activity para liberar memoria.
 */
public final class MapManager {

    // Nombre del directorio de caché en almacenamiento interno de la app.
    private static final String CACHE_NAME = "mapcache";

    // Zoom inicial razonable (calle/barrio). Se ajustará en fases posteriores.
    private static final byte INITIAL_ZOOM = 14;

    // Factor de overdraw del frame buffer. 1.0 = exactamente el tamaño de pantalla.
    // Un poco más alto da pan más fluido al coste de RAM. 1.0 es lo recomendado
    // para hardware modesto como nuestra radio de 1 GB.
    private static final float SCREEN_RATIO = 1.0f;

    private final Context context;
    private MapView mapView;
    private TileCache tileCache;
    private MapFile mapFile;

    public MapManager(@NonNull Context context) {
        // applicationContext: evita retener Activities y fugas asociadas.
        this.context = context.getApplicationContext();
    }

    /**
     * Asocia este manager a un MapView ya inflado desde XML, monta el cache de
     * tiles, abre el archivo .map y añade la capa renderizadora con el tema
     * interno por defecto. El mapa se centra inicialmente en el centro del
     * bounding box del archivo y un zoom medio.
     */
    public void attachToView(@NonNull MapView view, @NonNull File mapFilePath) {
        this.mapView = view;

        view.setClickable(true);
        view.getMapScaleBar().setVisible(true);
        // Sin controles +/- de zoom: la radio es táctil; usamos pinch.
        view.setBuiltInZoomControls(false);

        tileCache = AndroidUtil.createTileCache(
                context,
                CACHE_NAME,
                view.getModel().displayModel.getTileSize(),
                SCREEN_RATIO,
                view.getModel().frameBufferModel.getOverdrawFactor());

        mapFile = new MapFile(mapFilePath);

        TileRendererLayer tileRendererLayer = new TileRendererLayer(
                tileCache,
                mapFile,
                view.getModel().mapViewPosition,
                AndroidGraphicFactory.INSTANCE);
        // Tema interno: viene empaquetado en mapsforge-themes y no requiere assets.
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);
        view.getLayerManager().getLayers().add(tileRendererLayer);

        // Vista inicial: centro del mapa cargado, zoom de calle.
        view.setCenter(mapFile.boundingBox().getCenterPoint());
        view.setZoomLevel(INITIAL_ZOOM);
    }

    /**
     * Libera todos los recursos: caché de tiles, archivo .map y MapView.
     * Debe llamarse desde Activity.onDestroy().
     */
    public void destroy() {
        if (mapView != null) {
            // destroyAll() libera layers, mapFile y referencias internas.
            mapView.destroyAll();
            mapView = null;
        }
        if (tileCache != null) {
            tileCache.destroy();
            tileCache = null;
        }
        // mapFile se cierra dentro de destroyAll().
        mapFile = null;
    }
}
