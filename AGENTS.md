Guía de desarrollo y directrices para el proyecto de Launcher Android personalizado con integración de mapas y datos OBD2, optimizado específicamente para hardware de bajos recursos.

## Resumen del Proyecto
Creación de un **Launcher (Home App)** para radios Android chinas con memoria limitada (1GB RAM). El objetivo es centralizar la navegación (vía Mapsforge + GraphHopper) y la monitorización del vehículo (vía OBD2 Bluetooth) en una sola interfaz fluida y eficiente. Caso de uso principal: alternativa offline tipo Waze que muestre simultáneamente el mapa y métricas en vivo del coche (ej. consumo instantáneo).

## Stack Tecnológico
- **Lenguaje:** Java (sin Kotlin, para evitar el runtime extra).
- **minSdk:** 24 (Android 7.0). **targetSdk:** 28 (evita el bloat de AndroidX moderno y permisos runtime complejos de API 29+).
- **Mapas (renderizado):** Mapsforge offline con archivos `.map` de OpenStreetMap — **No Google Maps SDK**.
- **Routing offline:** GraphHopper (mismo ecosistema que Mapsforge). Cálculo de rutas sin red.
- **GPS:** `android.location.LocationManager` directo (la radio tiene GPS propio integrado). **No usar** FusedLocationProvider / Google Play Services.
- **OBD2:** `obd-java-api` sobre `BluetoothSocket` con UUID SPP estándar `00001101-0000-1000-8000-00805F9B34FB`. Guardar la MAC del ELM327 emparejado en `SharedPreferences` para reconexión directa sin escaneo.
- **Voz:** `android.speech.tts.TextToSpeech` nativo (peso 0, ya está en el sistema) para indicaciones de navegación.
- **Arquitectura:** Patrón de Servicio de Primer Plano (Foreground Service) para persistencia de la conexión OBD2 y GPS.
- **UI:** ViewBinding (sin DataBinding), Vector Drawables, Canvas para indicadores numéricos.

## Directrices de Optimización (Críticas)
- **Gestión de Memoria:** Máximo presupuesto de RAM para la app: **150MB-200MB**.
    - Evitar Inyección de Dependencias (Dagger/Hilt) y Reflection.
    - Prohibido el uso de `Enums` (usar `@IntDef` o constantes `static final`).
    - Minimizar la creación de objetos en el bucle de lectura del OBD2 (reutilizar objetos/buffers).
    - ProGuard/R8 con `minifyEnabled` y `shrinkResources` activos desde el día 1.
- **Rendimiento:**
    - UI Thread libre: la lectura OBD2, el GPS y el procesamiento de mapas en hilos separados.
    - Tasa de refresco: **30 FPS para el mapa** (panning fluido), **5-10 Hz para los indicadores OBD2** (consumo, RPM, etc.).
    - Mapas: renderizado offline para evitar consumo de datos y latencia de red.
    - Reconexión automática del Bluetooth si se cae (frecuente en coche).

## Reglas de Código
- **Nomenclatura:** camelCase para variables, PascalCase para clases. **No** usar el prefijo `m` para variables de clase (estilo AOSP antiguo, innecesario con IDEs modernos).
- **Persistencia:** No usar bases de datos pesadas (Room/SQLite). Usar `SharedPreferences` para configuraciones mínimas (MAC del ELM327, última posición GPS, ruta del archivo `.map`, etc.).

## UX en coche
- **Modo día/noche** (a definir más adelante: por hora o por sensor de luz).
- **WakeLock + `android:keepScreenOn="true"`** en la actividad principal: la pantalla nunca debe apagarse durante la conducción.
- **Acceso de Emergencia:** mantener siempre un acceso directo a `com.android.settings` en el layout principal.

## Datos offline necesarios
- Archivo `.map` de Mapsforge de la zona (download.mapsforge.org).
- Grafo `.ghz` de GraphHopper correspondiente a la misma zona.
- Almacenar preferiblemente en SD externa (España entera ≈ 500MB-1GB).

## Comandos y Flujo
- **Instalación como Launcher:** asegurar `<category android:name="android.intent.category.HOME"/>` en el Manifest.
- **Depuración de RAM:** `adb shell dumpsys meminfo [package_name]` para monitorizar consumo en tiempo real.
- **Test de conexión OBD2:** comando AT inicial `ATZ` (reset) → debe responder `ELM327 v...`.

## Forma de programar
- **Delega todo el tema de programacion al subagente