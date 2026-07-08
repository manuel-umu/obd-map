package obdmap.launcher.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Lanza la instalación de un APK ya descargado mediante el instalador del sistema
 */
public final class UpdateInstaller {

    private static final String FILEPROVIDER_SUFFIX = ".fileprovider";

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private UpdateInstaller() {
    }

    public static boolean canInstall(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /**
     * Abre la pantalla del sistema para conceder "orígenes desconocidos" a esta app
     */
    public static void openUnknownSourcesSettings(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * Lanza el instalador del sistema para {@code apk}
     */
    public static void install(@NonNull Context context, @NonNull File apk) {
        Uri apkUri = FileProvider.getUriForFile(
                context, context.getPackageName() + FILEPROVIDER_SUFFIX, apk);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, APK_MIME);
        // GRANT_READ para que el instalador pueda leer el content: URI
        // NEW_TASK porque puede lanzarse desde un contexto sin pila de Activities
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
