package obdmap.launcher.ui;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import obdmap.launcher.R;

import java.util.List;

/**
 * Adaptador de la lista de dispositivos Bluetooth de Ajustes. Trabaja sobre
 * la misma lista que mantiene la Activity y resalta el dispositivo elegido
 * como adaptador OBD.
 *
 * Los colores se resuelven una sola vez en el constructor: la lista se
 * repinta mucho durante el escaneo y no queremos consultar resources por fila.
 */
final class BtDevicesAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<BluetoothDevice> devices;

    // Colores precalculados (evita resolver resources por fila).
    private final int colorSelected;
    private final int colorBonded;
    private final int colorBondedDay;
    private final int colorUnpaired;
    private final int colorBadgePaired;
    private final int colorBadgeUnpaired;
    private final int colorName;
    private final int colorNameDay;
    private final int colorMac;
    private final int colorMacDay;

    /** MAC seleccionada actualmente como adaptador OBD; null si ninguna. */
    @Nullable private String selectedMac;

    private boolean nightMode = true;

    /**
     * @param context contexto de la Activity (para el inflater y los colores)
     * @param devices lista compartida; la Activity la modifica y después
     *                llama a notifyDataSetChanged()
     */
    BtDevicesAdapter(@NonNull Context context, @NonNull List<BluetoothDevice> devices) {
        this.inflater = LayoutInflater.from(context);
        this.devices  = devices;

        colorSelected = ContextCompat.getColor(context, R.color.primary_dark);
        colorBonded = ContextCompat.getColor(context, R.color.surface_dark);
        colorBondedDay = ContextCompat.getColor(context, R.color.surface_day);
        colorUnpaired = ContextCompat.getColor(context, R.color.surface_unpaired);
        colorBadgePaired = ContextCompat.getColor(context, R.color.text_paired);
        colorBadgeUnpaired = ContextCompat.getColor(context, R.color.text_unpaired);
        colorName = ContextCompat.getColor(context, R.color.text_primary);
        colorNameDay = ContextCompat.getColor(context, R.color.text_primary_day);
        colorMac = ContextCompat.getColor(context, R.color.text_secondary);
        colorMacDay = ContextCompat.getColor(context, R.color.text_secondary_day);
    }

    void setSelectedMac(@Nullable String mac) {
        this.selectedMac = mac;
    }

    void setNightMode(boolean night) {
        this.nightMode = night;
    }

    @Override
    public int getCount() {
        return devices.size();
    }

    @Override
    public BluetoothDevice getItem(int position) {
        return devices.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_bt_device, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        BluetoothDevice device = devices.get(position);
        String name = device.getName();
        holder.nameView.setText(name != null ? name : device.getAddress());
        holder.macView.setText(device.getAddress());

        boolean bonded   = device.getBondState() == BluetoothDevice.BOND_BONDED;
        boolean selected = device.getAddress().equals(selectedMac);

        if (selected) {
            convertView.setBackgroundColor(colorSelected);
        } else if (bonded) {
            convertView.setBackgroundColor(nightMode ? colorBonded : colorBondedDay);
        } else {
            convertView.setBackgroundColor(colorUnpaired);
        }

        // Solo la fila emparejada sin seleccionar sigue el tema; las otras dos
        // conservan su fondo oscuro con significado y piden texto claro.
        boolean lightText = nightMode || selected || !bonded;
        holder.nameView.setTextColor(lightText ? colorName : colorNameDay);
        holder.macView.setTextColor(lightText ? colorMac : colorMacDay);

        if (bonded) {
            holder.bondBadge.setText(R.string.settings_badge_paired);
            holder.bondBadge.setTextColor(colorBadgePaired);
        } else {
            holder.bondBadge.setText(R.string.settings_badge_unpaired);
            holder.bondBadge.setTextColor(colorBadgeUnpaired);
        }

        return convertView;
    }

    /** Caché de las vistas de cada fila (patrón ViewHolder). */
    private static final class ViewHolder {
        final TextView nameView;
        final TextView macView;
        final TextView bondBadge;

        ViewHolder(@NonNull View itemView) {
            nameView  = itemView.findViewById(R.id.deviceName);
            macView   = itemView.findViewById(R.id.deviceMac);
            bondBadge = itemView.findViewById(R.id.deviceBondBadge);
        }
    }
}
