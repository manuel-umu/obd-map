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
# Mapsforge (Fase 1)
# -----------------------------------------------------------------------------
# Mapsforge usa serialización Java en algunos componentes internos (caches,
# render themes). Mantenemos sus clases públicas para evitar fallos en runtime.
-keep class org.mapsforge.** { *; }
-keep interface org.mapsforge.** { *; }
-dontwarn org.mapsforge.**

# AndroidSVG: dependencia transitiva usada por algunos themes de Mapsforge.
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
# Reglas de obd-java-api     — Se añadirán en la Fase 2.
# Reglas de GraphHopper      — Se añadirán en la Fase 4.
# -----------------------------------------------------------------------------
