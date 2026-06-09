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
 * la misma lista que mantiene la Activity (referencia compartida, no copia)
 * y resalta el dispositivo elegido como adaptador OBD.
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
    private final int colorUnpaired;
    private final int colorBadgePaired;
    private final int colorBadgeUnpaired;

    /** MAC seleccionada actualmente como adaptador OBD; null si ninguna. */
    @Nullable private String selectedMac;

    /**
     * @param context contexto de la Activity (para el inflater y los colores)
     * @param devices lista compartida; la Activity la modifica y después
     *                llama a notifyDataSetChanged()
     */
    BtDevicesAdapter(@NonNull Context context, @NonNull List<BluetoothDevice> devices) {
        this.inflater = LayoutInflater.from(context);
        this.devices  = devices;

        colorSelected      = ContextCompat.getColor(context, R.color.primary_dark);
        colorBonded        = ContextCompat.getColor(context, R.color.surface_dark);
        colorUnpaired      = ContextCompat.getColor(context, R.color.surface_unpaired);
        colorBadgePaired   = ContextCompat.getColor(context, R.color.text_paired);
        colorBadgeUnpaired = ContextCompat.getColor(context, R.color.text_unpaired);
    }

    /** Cambia la MAC resaltada. Llamar a notifyDataSetChanged() después. */
    void setSelectedMac(@Nullable String mac) {
        this.selectedMac = mac;
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
            convertView.setBackgroundColor(colorBonded);
        } else {
            convertView.setBackgroundColor(colorUnpaired);
        }

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
