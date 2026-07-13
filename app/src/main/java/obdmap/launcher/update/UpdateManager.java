package obdmap.launcher.update;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.Locale;

import obdmap.launcher.R;

/**
 * Controlador de la auto-actualización OTA
 */
public final class UpdateManager {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    // Diálogo de progreso vivo durante la descarga (para actualizar/cerrar).
    @Nullable private AlertDialog progressDialog;
    @Nullable private ProgressBar progressBar;

    /**
     * Comprueba si hay actualización
     */
    public void checkOnStartup(@NonNull final Activity activity) {
        // Comprobación en cada apertura (sin throttle). Si no hay red, se omite en silencio.
        if (!isNetworkAvailable(activity)) {
            return;
        }

        new UpdateChecker().check(new UpdateChecker.CheckListener() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                if (isAlive(activity)) {
                    promptInstall(activity, info);
                }
            }
            @Override
            public void onNoUpdate() {
            }
            @Override
            public void onError(@NonNull String message) {
            }
        });
    }

    // -------------------------------------------------------------------------
    // Flujo de UI
    // -------------------------------------------------------------------------

    private void promptInstall(@NonNull final Activity activity, @NonNull final UpdateInfo info) {
        String sizeText = info.sizeBytes > 0
                ? String.format(Locale.getDefault(), "%.1f MB",
                        (double) info.sizeBytes / BYTES_PER_MB)
                : "";
        String message = activity.getString(
                R.string.update_available_message, info.versionName, sizeText);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(R.string.update_button_install,
                        (dialog, which) -> downloadAndInstall(activity, info))
                .setNegativeButton(R.string.update_button_later, null)
                .show();
    }

    /**
     * Descarga el APK de {@code info} (con diálogo de progreso) y, al terminar,
     * lanza su instalación. Público para reutilizarlo desde la pantalla de
     * Información (comprobación manual), además del flujo automático de arranque.
     */
    public void downloadAndInstall(@NonNull final Activity activity, @NonNull UpdateInfo info) {
        showProgressDialog(activity);

        new UpdateDownloader().download(activity, info, new UpdateDownloader.DownloadListener() {
            @Override
            public void onProgress(int percent) {
                if (progressBar != null) {
                    progressBar.setProgress(percent);
                }
            }
            @Override
            public void onComplete(@NonNull File apk) {
                dismissProgressDialog();
                if (!isAlive(activity)) {
                    return;
                }
                if (UpdateInstaller.canInstall(activity)) {
                    UpdateInstaller.install(activity, apk);
                } else {
                    promptUnknownSources(activity);
                }
            }
            @Override
            public void onError(@NonNull String message) {
                dismissProgressDialog();
                if (isAlive(activity)) {
                    new AlertDialog.Builder(activity)
                            .setMessage(activity.getString(
                                    R.string.update_download_failed, message))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        });
    }

    /**
     * Si falta el permiso de "orígenes desconocidos", explica y lleva a los ajustes.
     * El APK ya descargado se reintentará en la próxima comprobación.
     */
    private void promptUnknownSources(@NonNull final Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_unknown_sources_title)
                .setMessage(R.string.update_unknown_sources_message)
                .setPositiveButton(R.string.update_button_open_settings,
                        (dialog, which) -> UpdateInstaller.openUnknownSourcesSettings(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showProgressDialog(@NonNull Activity activity) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(false);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading_title)
                .setView(bar)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.CENTER);
        }
        dialog.show();

        progressDialog = dialog;
        progressBar = bar;
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        progressBar = null;
    }

    // Auxiliares

    private static boolean isNetworkAvailable(@NonNull Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        // getActiveNetworkInfo está deprecado en API 29+, pero targetSdk es 28 y es
        // la vía más ligera; suficiente para decidir si intentar la comprobación.
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /** true si la Activity sigue viva y se le pueden mostrar diálogos. */
    private static boolean isAlive(@NonNull Activity activity) {
        if (activity.isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return !activity.isDestroyed();
        }
        return true;
    }
}
