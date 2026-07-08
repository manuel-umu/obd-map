/**
 * Auto-actualización OTA de la app (Fase 10).
 *
 * <p>Comprueba GitHub Releases, descarga el APK más reciente y lanza su
 * instalación por el instalador del sistema, sin cables ni Play Store. Cero
 * dependencias externas: {@code HttpURLConnection} + {@code org.json}.</p>
 *
 * <ul>
 *   <li>{@link obdmap.launcher.update.UpdateManager} — orquestador y punto de entrada.</li>
 *   <li>{@link obdmap.launcher.update.UpdateChecker} — consulta la release más reciente.</li>
 *   <li>{@link obdmap.launcher.update.UpdateDownloader} — descarga el APK con progreso.</li>
 *   <li>{@link obdmap.launcher.update.UpdateInstaller} — lanza la instalación vía FileProvider.</li>
 *   <li>{@link obdmap.launcher.update.UpdateInfo} — datos de la release.</li>
 * </ul>
 */
package obdmap.launcher.update;
