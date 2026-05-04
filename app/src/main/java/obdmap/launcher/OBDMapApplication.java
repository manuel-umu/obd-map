package com.obdmap.launcher;

import android.app.Application;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;

/**
 * Application class del launcher. Su única responsabilidad es inicializar el
 * {@code AndroidGraphicFactory} de Mapsforge ANTES de que se infle cualquier
 * {@code MapView} (incluso desde XML), porque el factory es un singleton que
 * la librería necesita para crear bitmaps, paths y otros recursos gráficos.
 */
public final class OBDMapApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AndroidGraphicFactory.createInstance(this);
    }
}
