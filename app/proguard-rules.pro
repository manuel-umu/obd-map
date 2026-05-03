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
# Reglas de Mapsforge        — Se añadirán en la Fase 1.
# Reglas de obd-java-api     — Se añadirán en la Fase 2.
# Reglas de GraphHopper      — Se añadirán en la Fase 4.
# -----------------------------------------------------------------------------
