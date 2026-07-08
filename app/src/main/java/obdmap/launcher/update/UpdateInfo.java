package obdmap.launcher.update;

import androidx.annotation.NonNull;

public final class UpdateInfo {
    public final int versionCode;
    @NonNull public final String versionName;
    @NonNull public final String downloadUrl;
    public final long sizeBytes;
    public UpdateInfo(int versionCode, @NonNull String versionName,
                      @NonNull String downloadUrl, long sizeBytes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.downloadUrl = downloadUrl;
        this.sizeBytes = sizeBytes;
    }
}
