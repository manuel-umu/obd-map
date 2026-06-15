# =============================================================================
# Reglas ProGuard / R8 — proyecto OBD-Map
# =============================================================================
# ProGuard está activo desde el día 1 (Fase 0) para mantener el APK pequeño y
# el consumo de memoria dentro del presupuesto de 150-200 MB.
#
# A medida que se integren librerías en fases posteriores se añadirán aquí
# las reglas de keep necesarias. Cada bloque debe documentar POR QUÉ existe.
# =============================================================================

# -----------------------------------------------------------------------------
# Optimizaciones generales
# -----------------------------------------------------------------------------
# Mantener atributos útiles para stack traces legibles en crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Eliminación de logs en release
# -----------------------------------------------------------------------------
# Elimina llamadas a android.util.Log.* en builds release. Reduce APK y RAM.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

# -----------------------------------------------------------------------------
# VTM / oscim (sustituye a Mapsforge desde la Fase 1-VTM)
# -----------------------------------------------------------------------------
# VTM usa JNI para el renderer OpenGL (libvtm-jni.so / libgdx.so).
# Las clases nativas se localizan por nombre desde C++, así que R8 no puede
# renombrarlas. Mantenemos todo el paquete oscim para no romper ningún enlace.
-keep class org.oscim.** { *; }
-keep interface org.oscim.** { *; }
-dontwarn org.oscim.**

# VTM arrastra vtm-gdx que usa libGDX internamente para la carga de recursos.
# Mantenemos las clases gdx que VTM referencia para evitar NoClassDefFoundError.
-keep class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**

# MapFileTileSource lee la cabecera del .map y carga MapInfo vía reflexión interna.
# Sin este keep, R8 elimina los setters que el reader usa dinámicamente.
-keepclassmembers class org.oscim.tiling.source.mapfile.** { *; }

# AndroidSVG: dependencia transitiva de vtm-themes (igual que en Mapsforge).
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# Reglas estándar para clases serializables (necesario para los caches).
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# -----------------------------------------------------------------------------
# Paquete OBD2 propio (Bloque B, Fase 2)
# -----------------------------------------------------------------------------
# BluetoothObdReader usa BluetoothSocket y streams estándar de Java: no hay
# reflection interna, así que no necesita -keep. Solo conservamos los nombres
# de las clases públicas para que los stack traces sean legibles.
-keep class obdmap.launcher.obd.ObdState { *; }
-keep class obdmap.launcher.obd.ObdListener { *; }
-keep class obdmap.launcher.obd.BluetoothObdReader { *; }

# -----------------------------------------------------------------------------
# Paquete service (Bloque C, Fase 2)
# -----------------------------------------------------------------------------
# ObdService es referenciado desde el Manifest por nombre de clase; sin este
# -keep R8 puede renombrarlo y el sistema no lo encontraría al arrancar.
-keep class obdmap.launcher.service.ObdService { *; }
-keep interface obdmap.launcher.service.ObdServiceListener { *; }

# ObdDebugActivity también referenciada desde el Manifest.
-keep class obdmap.launcher.ui.ObdDebugActivity { *; }

# -----------------------------------------------------------------------------
# Reglas de GraphHopper      — Se añadirán en la Fase 4.
# -----------------------------------------------------------------------------
