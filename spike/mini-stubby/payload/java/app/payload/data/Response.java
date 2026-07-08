package app.payload.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One recorded survey visit: site, timestamp (doubles as the id) and the
 * 1..5 rating per question. JSON round-trips for SharedPreferences storage.
 */
public final class Response {

    /** Creation time in epoch millis — also the unique id. */
    public final long id;
    public final String siteName;
    public final int[] ratings;

    public Response(long id, String siteName, int[] ratings) {
        this.id = id;
        this.siteName = siteName;
        this.ratings = ratings.clone();
    }

    /** Overall score as 0..100 (average rating over the 5-point scale). */
    public int scorePercent() {
        if (ratings.length == 0) {
            return 0;
        }
        int sum = 0;
        for (int r : ratings) {
            sum += r;
        }
        return Math.round(100f * sum / (ratings.length * 5f));
    }

    public String dateLabel() {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(new Date(id));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("site", siteName);
        JSONArray arr = new JSONArray();
        for (int r : ratings) {
            arr.put(r);
        }
        o.put("ratings", arr);
        return o;
    }

    public static Response fromJson(JSONObject o) throws JSONException {
        JSONArray arr = o.getJSONArray("ratings");
        int[] ratings = new int[arr.length()];
        for (int i = 0; i < ratings.length; i++) {
            ratings[i] = arr.getInt(i);
        }
        return new Response(o.getLong("id"), o.getString("site"), ratings);
    }
}
