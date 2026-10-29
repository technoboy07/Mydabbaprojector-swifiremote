package com.m2smartremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.m2smartremote.core.SessionStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists paired sessions in app-private SharedPreferences so phones stay paired across
 * restarts. Only this app's UID can read the file. Excluded from backups (allowBackup=false).
 */
final class PrefsSessionStore implements SessionStore {
    private static final String TAG = "M2Remote";
    private static final String FILE = "sessions";
    private static final String KEY = "sessions_v1";

    private final SharedPreferences sp;

    PrefsSessionStore(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override
    public List<Record> load() {
        List<Record> out = new ArrayList<>();
        String raw = sp.getString(KEY, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Record(o.getString("id"), o.getString("key"), o.optString("name", ""),
                        o.optString("ip", ""), o.getLong("created"), o.getLong("seen")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Discarding unreadable session store");
            sp.edit().remove(KEY).apply();
        }
        return out;
    }

    @Override
    public void save(List<Record> records) {
        JSONArray arr = new JSONArray();
        try {
            for (Record r : records) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("key", r.keyHex);
                o.put("name", r.name);
                o.put("ip", r.ip);
                o.put("created", r.createdAt);
                o.put("seen", r.lastSeenAt);
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
