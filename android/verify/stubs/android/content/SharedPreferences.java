package android.content;
public interface SharedPreferences {
    String getString(String key, String def);
    long getLong(String key, long def);
    boolean getBoolean(String key, boolean def);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putLong(String key, long value);
        Editor putBoolean(String key, boolean value);
        void apply();
    }
}
