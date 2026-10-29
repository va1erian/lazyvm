package lazy.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The process environment behind System.getenv. */
public final class Environment extends HashMap<String, String> {
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
        String all = VM.environment();
        int i = 0;
        while (i < all.length()) {
            int end = all.indexOf('\0', i);
            String entry = all.substring(i, end);
            int eq = entry.indexOf('=');
            env.put(entry.substring(0, eq), entry.substring(eq + 1));
            i = end + 1;
        }
        return env;
    }

    /** Windows looks single names up ignoring case; the map itself stays exact. */
    public static String lookup(String name) {
        Map<String, String> env = map();
        String exact = env.get(name);
        if (exact != null || !VM.getProperty("os.name").startsWith("Windows")) {
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

    private static String checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        return (String) key;
    }
}
