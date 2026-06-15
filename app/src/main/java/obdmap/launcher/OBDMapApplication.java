package obdmap.launcher;

import android.app.Application;

/**
 * Application de la app.
 *
 * VTM no necesita ninguna inicialización global en Application:
 * su contexto gráfico se crea dentro de MapView.onSurfaceCreated().
 * La clase queda aquí como punto de entrada por si en el futuro
 * hay que inicializar algo más (crash reporter, etc.).
 */
public final class OBDMapApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Sin inicialización de VTM aquí: MapView se encarga al inflarse.
    }
}
