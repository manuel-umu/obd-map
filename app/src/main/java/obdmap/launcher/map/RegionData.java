package obdmap.launcher.map;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Descriptor de una región de datos offline.
 * <p>Una región agrupa el {@code .map} de Mapsforge y el grafo de GraphHopper.
 */
public final class RegionData {

    /** Subdirectorio raíz bajo el que cuelga cada región. */
    private static final String REGIONS_DIR = "regions";

    /** Subdirectorio del grafo dentro de la carpeta de la región. */
    private static final String GRAPH_DIR = "graph";

    /** Identificador estable; es también el nombre de su carpeta en disco. */
    public final String id;

    /** Nombre legible para la UI. */
    public final String displayName;

    /** Nombre del fichero .map dentro de la carpeta de la región. */
    public final String mapFileName;

    /** URL de descarga del .map. */
    public final String mapUrl;

    /** Zip del grafo en assets, o null si la región no viene empaquetada. */
    @Nullable
    public final String graphAssetZip;

    /**
     * Fecha del extracto OSM del que salieron el .map y el grafo. Al cambiar,
     * el grafo instalado se considera obsoleto y se vuelve a extraer.
     */
    public final String dataVersion;

    private RegionData(@NonNull String id,
                       @NonNull String displayName,
                       @NonNull String mapFileName,
                       @NonNull String mapUrl,
                       @Nullable String graphAssetZip,
                       @NonNull String dataVersion) {
        this.id            = id;
        this.displayName   = displayName;
        this.mapFileName   = mapFileName;
        this.mapUrl        = mapUrl;
        this.graphAssetZip = graphAssetZip;
        this.dataVersion   = dataVersion;
    }

    // ---------------------------------------------------------------------
    // Regiones conocidas
    // ---------------------------------------------------------------------

    public static final RegionData MURCIA = new RegionData(
            "murcia",
            "Región de Murcia",
            "murcia.map",
            "https://download.mapsforge.org/maps/v5/europe/spain/murcia.map",
            "murcia-gh.zip",
            "2026-06-23");

    private static final RegionData[] ALL = { MURCIA };

    /** Copia del registro de regiones conocidas. */
    @NonNull
    public static RegionData[] all() {
        return ALL.clone();
    }

    /** Región usada mientras el usuario no elija otra. */
    @NonNull
    public static RegionData getDefault() {
        return MURCIA;
    }

    /** Busca por identificador; null si no existe o el id es null. */
    @Nullable
    public static RegionData byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (RegionData region : ALL) {
            if (region.id.equals(id)) {
                return region;
            }
        }
        return null;
    }

    /** La región indicada, o la de por defecto si el id no se reconoce. */
    @NonNull
    public static RegionData byIdOrDefault(@Nullable String id) {
        RegionData region = byId(id);
        return (region != null) ? region : getDefault();
    }

    // ---------------------------------------------------------------------
    // Rutas en disco
    // ---------------------------------------------------------------------

    @NonNull
    public static File regionsRoot(@NonNull Context ctx) {
        File external = ctx.getExternalFilesDir(REGIONS_DIR);
        if (external != null) {
            return external;
        }
        return new File(ctx.getFilesDir(), REGIONS_DIR);
    }

    /** Carpeta de esta región: contiene su .map y su grafo. */
    @NonNull
    public File dir(@NonNull Context ctx) {
        return new File(regionsRoot(ctx), id);
    }

    /** Carpeta del grafo de GraphHopper de esta región. */
    @NonNull
    public File graphDir(@NonNull Context ctx) {
        return new File(dir(ctx), GRAPH_DIR);
    }

    /** Fichero .map de esta región. */
    @NonNull
    public File mapFile(@NonNull Context ctx) {
        return new File(dir(ctx), mapFileName);
    }
}
