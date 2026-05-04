package com.obdmap.launcher.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.obdmap.launcher.R;
import com.obdmap.launcher.databinding.ActivitySettingsBinding;
import com.obdmap.launcher.prefs.PrefsManager;

import java.util.ArrayList;
import java.util.Set;

/**
 * Pantalla de ajustes del Bloque A (Fase 2). Permite al usuario seleccionar
 * el adaptador ELM327 de entre los dispositivos Bluetooth ya emparejados
 * en el sistema y persistir su MAC en {@link PrefsManager}.
 *
 * <p>No se usa un Intent de escaneo activo ni se fuerza el encendido del BT
 * programáticamente: solo se lista lo que el sistema ya tiene emparejado.</p>
 */
public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private PrefsManager prefsManager;

    // Lista mutable que alimenta el adaptador; se rellena en onResume
    // para reflejar cambios si el usuario volvió de ajustes BT.
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private PairedDevicesAdapter listAdapter;

    // MAC guardada actualmente (puede ser null si no hay ninguna).
    @Nullable private String currentMac;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);

        listAdapter = new PairedDevicesAdapter();
        binding.pairedDevicesList.setAdapter(listAdapter);

        binding.backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        binding.clearSelectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefsManager.clearObdMac();
                currentMac = null;
                refreshSelectedLabel();
                // Forzamos redibujado de la lista para quitar el resaltado.
                listAdapter.notifyDataSetChanged();
            }
        });

        binding.openBtSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    // Muchas radios chinas eliminan la pantalla de ajustes BT;
                    // intentamos los ajustes generales como último recurso.
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (ActivityNotFoundException e2) {
                        Toast.makeText(SettingsActivity.this,
                                R.string.settings_no_bt_settings_app,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        binding.pairedDevicesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BluetoothDevice device = pairedDevices.get(position);
                String mac = device.getAddress();
                prefsManager.setObdMac(mac);
                currentMac = mac;
                refreshSelectedLabel();
                listAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Releemos la MAC guardada por si cambió mientras estaba en otra pantalla.
        currentMac = prefsManager.getObdMac();
        refreshSelectedLabel();
        refreshPairedDevicesList();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------------
    // Lógica interna
    // ------------------------------------------------------------------------

    /**
     * Actualiza el chip de selección actual con la MAC guardada, o muestra
     * "Ninguno seleccionado" si no hay ninguna.
     */
    private void refreshSelectedLabel() {
        if (currentMac == null || currentMac.isEmpty()) {
            binding.selectedDeviceText.setText(R.string.settings_none_selected);
        } else {
            // Buscamos el nombre del dispositivo para mostrarlo junto a la MAC.
            String label = findDeviceName(currentMac);
            binding.selectedDeviceText.setText(
                    getString(R.string.settings_selected_label, label + "  " + currentMac));
        }
    }

    /**
     * Recorre la lista en memoria para encontrar el nombre asociado a una MAC.
     * Devuelve la MAC como fallback si el dispositivo ya no está emparejado.
     */
    private String findDeviceName(@NonNull String mac) {
        for (int i = 0; i < pairedDevices.size(); i++) {
            if (mac.equals(pairedDevices.get(i).getAddress())) {
                return pairedDevices.get(i).getName();
            }
        }
        return mac;
    }

    /**
     * Consulta los dispositivos emparejados del sistema y actualiza la UI
     * según si el BT está encendido, no hay emparejados, o hay lista normal.
     */
    private void refreshPairedDevicesList() {
        pairedDevices.clear();

        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();

        if (btAdapter == null || !btAdapter.isEnabled()) {
            // BT apagado o no disponible en este hardware.
            showMessage(getString(R.string.settings_bt_off), true);
            listAdapter.notifyDataSetChanged();
            return;
        }

        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            showMessage(getString(R.string.settings_no_paired), true);
            listAdapter.notifyDataSetChanged();
            return;
        }

        // Hay dispositivos emparejados: llenar la lista y ocultar el mensaje.
        for (BluetoothDevice device : bonded) {
            pairedDevices.add(device);
        }
        showMessage(null, false);
        listAdapter.notifyDataSetChanged();
    }

    /**
     * Alterna entre el mensaje de estado y la lista según si hay contenido
     * que mostrar o no.
     *
     * @param message  Texto del mensaje de estado, o {@code null} para ocultarlo.
     * @param showBtButton {@code true} para mostrar el botón de ajustes BT.
     */
    private void showMessage(@Nullable String message, boolean showBtButton) {
        if (message != null) {
            binding.statusMessage.setText(message);
            binding.statusMessage.setVisibility(View.VISIBLE);
            binding.pairedDevicesList.setVisibility(View.GONE);
        } else {
            binding.statusMessage.setVisibility(View.GONE);
            binding.pairedDevicesList.setVisibility(View.VISIBLE);
        }
        binding.openBtSettingsButton.setVisibility(showBtButton ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------------
    // Adaptador de la lista
    // ------------------------------------------------------------------------

    /**
     * Adaptador ligero para {@link android.widget.ListView}. Reutiliza vistas
     * correctamente para evitar inflados innecesarios durante el scroll.
     */
    private final class PairedDevicesAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return pairedDevices.size();
        }

        @Override
        public BluetoothDevice getItem(int position) {
            return pairedDevices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        R.layout.item_bt_device, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            BluetoothDevice device = pairedDevices.get(position);
            holder.nameView.setText(device.getName());
            holder.macView.setText(device.getAddress());

            // Resaltado visual del dispositivo actualmente seleccionado.
            boolean selected = device.getAddress().equals(currentMac);
            int bgColor = selected
                    ? getResources().getColor(R.color.primary_dark)
                    : getResources().getColor(R.color.surface_dark);
            convertView.setBackgroundColor(bgColor);

            return convertView;
        }
    }

    /** ViewHolder estático para que el compilador no genere referencias ocultas a la Activity. */
    private static final class ViewHolder {
        final TextView nameView;
        final TextView macView;

        ViewHolder(@NonNull View itemView) {
            nameView = itemView.findViewById(R.id.deviceName);
            macView  = itemView.findViewById(R.id.deviceMac);
        }
    }
}
