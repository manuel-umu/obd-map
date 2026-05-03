package com.obdmap.launcher.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.obdmap.launcher.databinding.ActivityMainBinding;

/**
 * Activity principal del launcher. En la Fase 0 solo muestra un placeholder y
 * el botón de acceso de emergencia a los ajustes del sistema. En fases
 * posteriores, este layout albergará el {@code MapView} de Mapsforge y los
 * indicadores OBD2 superpuestos.
 */
public final class MainActivity extends AppCompatActivity {

    // ViewBinding generado a partir de activity_main.xml.
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Listener anónimo (no lambda) para mantener consistencia con las reglas
        // del proyecto sobre creación de objetos en código sensible.
        binding.emergencyAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSystemSettings();
            }
        });
    }

    /**
     * Lanza la pantalla de Ajustes del sistema. Usar {@code FLAG_ACTIVITY_NEW_TASK}
     * porque al estar instalada como launcher (HOME) la salida natural a otra app
     * requiere abrir una nueva pila de tareas.
     */
    private void openSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        // Liberar la referencia para evitar fugas de memoria con el ViewBinding.
        binding = null;
        super.onDestroy();
    }

    /**
     * Sobrescribimos {@link #onBackPressed()} para que el botón "atrás" no cierre
     * el launcher (un launcher no debe poder "salirse"; es la pantalla raíz).
     */
    @Override
    public void onBackPressed() {
        // Intencionalmente vacío: el launcher es el HOME, no procede ir hacia atrás.
    }
}
