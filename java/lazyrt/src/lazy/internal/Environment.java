package lazy.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The process environment behind System.getenv. */
public final class Environment extends HashMap<String, String> {
    // Decided once, by the platform: a later change to os.name must not matter.
    private static final boolean IGNORE_CASE = VM.environmentIgnoresCase();
    private static Map<String, String> view;

    private Environment() {
    }

    /** The unmodifiable map: names compare exactly, and null or non-String keys are errors. */
    public static synchronized Map<String, String> map() {
        if (view == null) {
            view = Collections.unmodifiableMap(load());
        }
        return view;
    }

    private static Environment load() {
        Environment env = new Environment();
        String[] pairs = VM.environment();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return env;
    }

    /** Single lookups ignore case where the platform does; the map itself stays exact. */
    public static String lookup(String name) {
        Map<String, String> env = map();
        String exact = env.get(name);
        if (exact != null || !IGNORE_CASE) {
            return exact;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public String get(Object key) {
        return super.get(checkKey(key));
    }

    public boolean containsKey(Object key) {
        return super.containsKey(checkKey(key));
    }

    public boolean containsValue(Object value) {
        return super.containsValue(checkKey(value));
    }

    private static String checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        return (String) key;
    }
}
