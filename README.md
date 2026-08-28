# OBD-Map

**Un navegador offline con telemetría del coche en vivo, para radios Android de poca memoria RAM.**

OBD-Map sustituye la interfaz de una radio de coche *aftermarket* por una sola pantalla que junta dos cosas que normalmente van por separado: **la navegación** y **los datos del motor**. Mapa vectorial, rutas calculadas en el propio dispositivo, indicaciones por voz y el consumo instantáneo leído del coche por Bluetooth. Todo sin conexión a internet y sin servicios de Google.

> [!NOTE]
> Proyecto personal en desarrollo activo. La app corre a diario en un coche con una radio china de 1 GB de RAM y GPU Mali-450.

---

## Qué hace

- **Navegación 100% offline.** Mapa vectorial y cálculo de rutas en el dispositivo. Ni un byte de red.
- **Guía giro a giro** con avisos por voz en español, distancia a la siguiente maniobra y hora estimada de llegada.
- **Telemetría del coche en tiempo real** vía adaptador OBD2 Bluetooth (ELM327): consumo instantáneo, velocidad, régimen, temperaturas y presión de admisión.
- **Sitios guardados** con un toque, marcados en el mapa y con ruta directa desde un desplegable.
- **Modo día/noche** con temas de mapa y de interfaz propios.
- **Auto-actualización OTA** desde GitHub Releases, sin cables ni tienda de aplicaciones.

---

## Arquitectura

```
ui/       Activities y vistas dibujadas con Canvas
map/      VTM, temas, descarga de mapas, selección de destino
routing/  GraphHopper, seguimiento de ruta, map-matching
obd/      Driver ELM327, decodificación de PIDs, modelo de consumo
service/  Manejador de la conexión OBD
gps/      Manejador de la señal GPS
voice/    TextToSpeech nativo en español
prefs/    Persistencia sobre SharedPreferences de Android
update/   Actualización desde GitHub Releases
```

---

## Stack

`Java 8` · `Android SDK 24-28` · `VTM 0.25` · `GraphHopper 1.0` · `OpenGL ES 2.0` · `Bluetooth SPP` · `Gradle 7.6 / AGP 7.4`

---

## Compilar

Requiere **JDK 17**.

```bash
./gradlew :app:assembleDebug
```
---

## Estado

En desarrollo activo y en uso real. El roadmap inmediato pasa por validar en carretera el consumo del coche y cerrar los últimos problemas de fluidez del render.

En cuanto a roadmap futuro, esta planeado añadir al modelo **offline** una parte **online**, para quitar peso al cálculo de las rutas y añadir funcionalidades como aviso de radares, obras y retenciones, cálculo de ruta introduciendo un nombre, y mapa global. 
