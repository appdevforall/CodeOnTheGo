package app.payload.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SharedPreferences-backed store of survey visits (stressor 9: persistence
 * across hot reloads AND host restarts). Note the prefs file lives in the
 * SHELL app's data dir — the payload is never installed, so it has no data
 * dir of its own; all payloads share the host's storage namespace.
 */
public final class ResponseStore {

    private static final String TAG = "FieldSurvey";
    private static final String PREFS = "fieldsurvey";
    private static final String KEY = "responses.v1";

    private final SharedPreferences prefs;
    /** Newest-first, mirrors what the home list shows. */
    private final List<Response> cache = new ArrayList<>();

    public ResponseStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadFromDisk();
    }

    private void loadFromDisk() {
        cache.clear();
        String raw = prefs.getString(KEY, null);
        if (raw == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                cache.add(Response.fromJson(arr.getJSONObject(i)));
            }
            cache.sort(Comparator.comparingLong((Response r) -> r.id).reversed());
        } catch (Exception e) {
            // Corrupt store: keep whatever parsed, don't crash the payload.
            Log.w(TAG, "response store corrupt, partial load (" + cache.size() + ")", e);
        }
    }

    /** Newest-first snapshot. */
    public List<Response> all() {
        return new ArrayList<>(cache);
    }

    public Response byId(long id) {
        for (Response r : cache) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    public void add(Response r) {
        cache.add(0, r);
        persist();
    }

    public boolean delete(long id) {
        boolean removed = cache.removeIf(r -> r.id == id);
        if (removed) {
            persist();
        }
        return removed;
    }

    /** Overall scores in chronological order — feeds the sparkline. */
    public int[] scoreHistory() {
        int n = cache.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = cache.get(n - 1 - i).scorePercent();
        }
        return out;
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Response r : cache) {
                arr.put(r.toJson());
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "response store persist failed", e);
        }
    }
}
