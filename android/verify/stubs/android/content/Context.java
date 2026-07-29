package android.content;
import java.util.HashMap;
import java.util.Map;
/** In-memory stand-in so the store's logic can actually be exercised off-device. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final Map<String, Map<String, Object>> FILES = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        final Map<String, Object> data = FILES.computeIfAbsent(name, k -> new HashMap<>());
        return new SharedPreferences() {
            public String getString(String k, String d) { Object v = data.get(k); return v == null ? d : (String) v; }
            public long getLong(String k, long d) { Object v = data.get(k); return v == null ? d : (Long) v; }
            public boolean getBoolean(String k, boolean d) { Object v = data.get(k); return v == null ? d : (Boolean) v; }
            public Editor edit() {
                return new Editor() {
                    public Editor putString(String k, String v) { data.put(k, v); return this; }
                    public Editor putLong(String k, long v) { data.put(k, v); return this; }
                    public Editor putBoolean(String k, boolean v) { data.put(k, v); return this; }
                    public void apply() {}
                };
            }
        };
    }
    public static void reset() { FILES.clear(); }
}
