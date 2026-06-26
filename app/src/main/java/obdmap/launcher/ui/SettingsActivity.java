package obdmap.launcher.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import obdmap.launcher.R;
import obdmap.launcher.databinding.ActivitySettingsBinding;
import obdmap.launcher.prefs.PrefsManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

/**
 * Pantalla de ajustes: elegir el adaptador OBD. Lista los dispositivos
 * Bluetooth emparejados y los que aparecen al escanear, y permite emparejar
 * el ELM327 desde aquí mismo (con el PIN metido automáticamente), sin pelearse
 * con los ajustes Bluetooth del sistema de la radio.
 */
public final class SettingsActivity extends AppCompatActivity {

    /**
     * PIN de fábrica de los ELM327 (algunos usan "0000"). Se mete solo al
     * emparejar para ahorrarse el diálogo del sistema en la pantalla del coche.
     */
    private static final String ELM327_DEFAULT_PIN = "1234";

    private ActivitySettingsBinding binding;
    private PrefsManager prefsManager;

    // Todos los dispositivos mostrados: emparejados + encontrados en el escaneo.
    private final ArrayList<BluetoothDevice> allDevices = new ArrayList<>();
    private BtDevicesAdapter listAdapter;

    @Nullable private String currentMac;
    private boolean scanning = false;
    private boolean receiversRegistered = false;

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsManager = new PrefsManager(this);
        listAdapter = new BtDevicesAdapter(this, allDevices);
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
                listAdapter.setSelectedMac(null);
                refreshSelectedLabel();
                listAdapter.notifyDataSetChanged();
            }
        });

        binding.openBtSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (ActivityNotFoundException e) {
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

        binding.scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        binding.openObdDebugButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ObdDebugActivity.class));
            }
        });

        binding.pairedDevicesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BluetoothDevice device = allDevices.get(position);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    selectDevice(device);
                } else {
                    pairDevice(device);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentMac = prefsManager.getObdMac();
        listAdapter.setSelectedMac(currentMac);
        refreshSelectedLabel();
        refreshDeviceList();
        registerReceivers();
    }

    @Override
    protected void onPause() {
        stopScan();
        unregisterReceivers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    // =========================================================================
    // Escaneo y emparejado
    // =========================================================================

    private void startScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            showMessage(getString(R.string.settings_bt_off));
            return;
        }
        if (scanning) {
            return;
        }
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        setScanningUi(true);
        adapter.startDiscovery();
    }

    private void stopScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        setScanningUi(false);
    }

    private void pairDevice(@NonNull BluetoothDevice device) {
        // Cancelar discovery antes de emparejar: el discovery activo bloquea el bonding.
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        setScanningUi(false);
        device.createBond();
        Toast.makeText(this, R.string.settings_bonding, Toast.LENGTH_SHORT).show();
    }

    private void selectDevice(@NonNull BluetoothDevice device) {
        String mac = device.getAddress();
        prefsManager.setObdMac(mac);
        currentMac = mac;
        listAdapter.setSelectedMac(mac);
        refreshSelectedLabel();
        listAdapter.notifyDataSetChanged();
    }

    /** Pone el botón y el flag de escaneo en el estado que toca. */
    private void setScanningUi(boolean active) {
        scanning = active;
        if (binding == null) {
            return;
        }
        binding.scanButton.setText(active
                ? R.string.settings_scanning
                : R.string.settings_scan_button);
        binding.scanButton.setEnabled(!active);
    }

    // =========================================================================
    // BroadcastReceivers
    // =========================================================================

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    addOrUpdateDevice(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setScanningUi(false);
            }
        }
    };

    // Prioridad alta para suprimir el diálogo del sistema (broadcast ordenado).
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
            if (device != null && variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                device.setPin(ELM327_DEFAULT_PIN.getBytes(StandardCharsets.US_ASCII));
                try {
                    abortBroadcast();
                } catch (Exception ignored) {
                    // Best-effort: si no suprime el diálogo, el usuario verá
                    // el cuadro del sistema con el PIN ya pre-rellenado.
                }
            }
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (device == null) {
                return;
            }
            addOrUpdateDevice(device);

            String name = device.getName() != null ? device.getName() : device.getAddress();
            if (newState == BluetoothDevice.BOND_BONDED) {
                selectDevice(device);
                Toast.makeText(context,
                        getString(R.string.settings_bond_ok, name),
                        Toast.LENGTH_LONG).show();
            } else if (newState == BluetoothDevice.BOND_NONE) {
                Toast.makeText(context,
                        getString(R.string.settings_bond_fail, name),
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    private void registerReceivers() {
        if (receiversRegistered) {
            return;
        }
        IntentFilter scanFilter = new IntentFilter();
        scanFilter.addAction(BluetoothDevice.ACTION_FOUND);
        scanFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(scanReceiver, scanFilter);

        IntentFilter pairFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(pairingReceiver, pairFilter);

        registerReceiver(bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));

        receiversRegistered = true;
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) {
            return;
        }
        try { unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(pairingReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(bondReceiver); } catch (IllegalArgumentException ignored) {}
        receiversRegistered = false;
    }

    // =========================================================================
    // Helpers de lista y UI
    // =========================================================================

    private void refreshDeviceList() {
        allDevices.clear();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            showMessage(getString(R.string.settings_bt_off));
            listAdapter.notifyDataSetChanged();
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            allDevices.addAll(bonded);
        }
        if (allDevices.isEmpty()) {
            showMessage(getString(R.string.settings_no_paired));
        } else {
            showMessage(null);
        }
        listAdapter.notifyDataSetChanged();
    }

    private void addOrUpdateDevice(@NonNull BluetoothDevice device) {
        String mac = device.getAddress();
        for (int i = 0; i < allDevices.size(); i++) {
            if (mac.equals(allDevices.get(i).getAddress())) {
                allDevices.set(i, device);
                if (binding != null) {
                    showMessage(null);
                    listAdapter.notifyDataSetChanged();
                }
                return;
            }
        }
        allDevices.add(device);
        if (binding != null) {
            showMessage(null);
            listAdapter.notifyDataSetChanged();
        }
    }

    private void refreshSelectedLabel() {
        if (currentMac == null || currentMac.isEmpty()) {
            binding.selectedDeviceText.setText(R.string.settings_none_selected);
        } else {
            String label = findDeviceName(currentMac);
            binding.selectedDeviceText.setText(
                    getString(R.string.settings_selected_label, label + "  " + currentMac));
        }
    }

    private String findDeviceName(@NonNull String mac) {
        for (int i = 0; i < allDevices.size(); i++) {
            BluetoothDevice d = allDevices.get(i);
            if (mac.equals(d.getAddress())) {
                String name = d.getName();
                return name != null ? name : mac;
            }
        }
        return mac;
    }

    private void showMessage(@Nullable String message) {
        if (message != null) {
            binding.statusMessage.setText(message);
            binding.statusMessage.setVisibility(View.VISIBLE);
            binding.pairedDevicesList.setVisibility(View.GONE);
        } else {
            binding.statusMessage.setVisibility(View.GONE);
            binding.pairedDevicesList.setVisibility(View.VISIBLE);
        }
    }

}
