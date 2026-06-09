package obdmap.launcher;

import android.app.Application;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;

/**
 * Application de la app. Solo hace una cosa: inicializar el motor gráfico de
 * Mapsforge antes de que se infle ningún MapView (también los de XML), porque
 * la librería lo necesita listo para crear bitmaps y demás.
 */
public final class OBDMapApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AndroidGraphicFactory.createInstance(this);
    }
}
