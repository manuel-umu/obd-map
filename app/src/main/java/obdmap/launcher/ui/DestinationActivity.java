package obdmap.launcher.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivityDestinationBinding;
import obdmap.launcher.prefs.PrefsManager;
import obdmap.launcher.routing.RoutingManager;

/**
 * Pantalla para introducir manualmente las coordenadas del destino de ruta.
 * Valida la entrada y la persiste en SharedPreferences vía PrefsManager.
 */
public final class DestinationActivity extends AppCompatActivity {

    private ActivityDestinationBinding binding;
    private PrefsManager prefsManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDestinationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);

        binding.btnSetDestination.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onSetDestinationClicked();
            }
        });

        binding.btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        binding.btnClearDestination.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefsManager.clearDestination();
                Toast.makeText(DestinationActivity.this,
                        R.string.toast_destination_cleared, Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        // Si ya hay un destino guardado, lo mostramos en los campos al abrir.
        prefillSavedDestination();
    }

    /** Rellena los campos con el destino guardado, si lo hay. */
    private void prefillSavedDestination() {
        float dLat = prefsManager.getDestLat();
        float dLon = prefsManager.getDestLon();
        if (!Float.isNaN(dLat) && !Float.isNaN(dLon)) {
            binding.editLatitude.setText(String.valueOf(dLat));
            binding.editLongitude.setText(String.valueOf(dLon));
        }
    }

    /**
     * Valida las coordenadas introducidas por el usuario, las guarda y cierra la pantalla.
     * Muestra un Toast si los valores no son numéricos válidos.
     */
    private void onSetDestinationClicked() {
        String latText = binding.editLatitude.getText().toString().trim();
        String lonText = binding.editLongitude.getText().toString().trim();

        float lat;
        float lon;
        try {
            lat = Float.parseFloat(latText);
            lon = Float.parseFloat(lonText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.toast_invalid_coordinates, Toast.LENGTH_SHORT).show();
            return;
        }

        // Rango geográfico básico: latitud ±90, longitud ±180.
        if (lat < -90f || lat > 90f || lon < -180f || lon > 180f) {
            Toast.makeText(this, R.string.toast_invalid_coordinates, Toast.LENGTH_SHORT).show();
            return;
        }

        prefsManager.setDestination(lat, lon);

        // Inicia la carga del grafo en segundo plano si aun no ha comenzado.
        RoutingManager rm = RoutingManager.getInstance();
        if (rm.getState() == RoutingManager.STATE_IDLE) {
            rm.startLoading(this, new RoutingManager.RoutingListener() {
                @Override
                public void onRoutingReady() {
                    // El grafo estara listo cuando el usuario vuelva al mapa.
                }

                @Override
                public void onRoutingError(String message) {
                    // El error queda registrado en el estado del RoutingManager.
                }

                @Override
                public void onRoutingProgress(String status) {
                    // Sin UI de progreso en esta pantalla.
                }
            });
        }

        Toast.makeText(this, R.string.toast_destination_set, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
