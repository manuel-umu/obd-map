package obdmap.launcher.map;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Busqueda de lugares por nombre contra la API de Photon
 */
public final class PlaceSearch {
    private static final String API_URL = "https://photon.komoot.io/api";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS    = 8000;
    private static final int MAX_RESULTS        = 6;

    public static final class Place {
        public final String label;
        public final double lat;
        public final double lon;

        Place(@NonNull String label, double lat, double lon) {
            this.label = label;
            this.lat   = lat;
            this.lon   = lon;
        }
    }

    public interface Listener {
        void onResults(@NonNull List<Place> results);

        void onError(@NonNull String message);
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Descarta las respuestas de búsquedas ya superadas por otra más reciente. */
    private int sequence = 0;

    /**
     * Lanza una búsqueda en segundo plano
     *
     * @param query   texto tecleado por el usuario
     * @param nearLat latitud con la que sesgar los resultados, NaN si no hay GPS
     * @param nearLon longitud con la que sesgar los resultados, NaN si no hay GPS
     */
    @MainThread
    public void search(@NonNull final String query,
                       final double nearLat,
                       final double nearLon,
                       @NonNull final Listener listener) {
        final int mine = ++sequence;

        new Thread(() -> {
            try {
                final List<Place> results = request(query, nearLat, nearLon);
                uiHandler.post(() -> {
                    if (mine == sequence) {
                        listener.onResults(results);
                    }
                });
            } catch (final Exception e) {
                final String message = (e.getMessage() != null) ? e.getMessage() : "Error de búsqueda";
                uiHandler.post(() -> {
                    if (mine == sequence) {
                        listener.onError(message);
                    }
                });
            }
        }, "place-search").start();
    }

    @MainThread
    public void cancel() {
        sequence++;
    }

    @NonNull
    private static List<Place> request(@NonNull String query,
                                       double nearLat,
                                       double nearLon) throws Exception {
        StringBuilder url = new StringBuilder(API_URL)
                .append("?q=").append(URLEncoder.encode(query, "UTF-8"))
                .append("&limit=").append(MAX_RESULTS);
        if (!Double.isNaN(nearLat) && !Double.isNaN(nearLon)) {
            url.append("&lat=").append(nearLat).append("&lon=").append(nearLon);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("Error HTTP " + responseCode);
            }
            return parse(readBody(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static String readBody(@NonNull InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    @NonNull
    private static List<Place> parse(@NonNull String body) throws Exception {
        JSONArray features = new JSONObject(body).optJSONArray("features");
        List<Place> results = new ArrayList<>();
        if (features == null) {
            return results;
        }

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) {
                continue;
            }
            JSONObject geometry = feature.optJSONObject("geometry");
            if (geometry == null) {
                continue;
            }
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null || coordinates.length() < 2) {
                continue;
            }
            String label = buildLabel(feature.optJSONObject("properties"));
            if (label.isEmpty()) {
                continue;
            }
            results.add(new Place(label, coordinates.getDouble(1), coordinates.getDouble(0)));
        }
        return results;
    }

    /** Une nombre, calle y municipio en una sola línea */
    @NonNull
    private static String buildLabel(JSONObject properties) {
        if (properties == null) {
            return "";
        }

        StringBuilder main = new StringBuilder();
        String name = properties.optString("name", "");
        if (!name.isEmpty()) {
            main.append(name);
        } else {
            String street = properties.optString("street", "");
            if (!street.isEmpty()) {
                main.append(street);
                String number = properties.optString("housenumber", "");
                if (!number.isEmpty()) {
                    main.append(' ').append(number);
                }
            }
        }
        if (main.length() == 0) {
            return "";
        }

        String city = properties.optString("city", "");
        if (city.isEmpty()) {
            city = properties.optString("county", "");
        }
        if (!city.isEmpty() && !city.equals(main.toString())) {
            main.append(", ").append(city);
        }

        String state = properties.optString("state", "");
        if (!state.isEmpty() && !state.equals(city)) {
            main.append(", ").append(state);
        }
        return main.toString();
    }
}
