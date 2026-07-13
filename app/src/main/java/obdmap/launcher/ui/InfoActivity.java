package obdmap.launcher.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import obdmap.launcher.BuildConfig;
import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivityInfoBinding;
import obdmap.launcher.update.UpdateChecker;
import obdmap.launcher.update.UpdateInfo;
import obdmap.launcher.update.UpdateManager;

/**
 * Pantalla de Información
 */
public final class InfoActivity extends AppCompatActivity {
    private ActivityInfoBinding binding;
    private final UpdateManager updateManager = new UpdateManager();
    @Nullable private UpdateInfo availableUpdate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInfoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.infoVersionText.setText(getString(R.string.info_version_installed,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        binding.backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        binding.infoCheckButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });

        binding.infoInstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (availableUpdate != null) {
                    updateManager.downloadAndInstall(InfoActivity.this, availableUpdate);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }


    private void checkForUpdate() {
        availableUpdate = null;
        binding.infoCheckButton.setEnabled(false);
        binding.infoInstallButton.setVisibility(View.GONE);
        binding.infoResultText.setVisibility(View.VISIBLE);
        binding.infoResultText.setText(R.string.info_checking);

        new UpdateChecker().check(new UpdateChecker.CheckListener() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                if (binding == null) {
                    return;
                }
                availableUpdate = info;
                binding.infoResultText.setText(
                        getString(R.string.info_update_available, info.versionName));
                binding.infoInstallButton.setVisibility(View.VISIBLE);
                binding.infoCheckButton.setEnabled(true);
            }

            @Override
            public void onNoUpdate() {
                if (binding == null) {
                    return;
                }
                binding.infoResultText.setText(R.string.info_up_to_date);
                binding.infoInstallButton.setVisibility(View.GONE);
                binding.infoCheckButton.setEnabled(true);
            }

            @Override
            public void onError(@NonNull String message) {
                if (binding == null) {
                    return;
                }
                binding.infoResultText.setText(getString(R.string.info_check_failed, message));
                binding.infoInstallButton.setVisibility(View.GONE);
                binding.infoCheckButton.setEnabled(true);
            }
        });
    }
}
