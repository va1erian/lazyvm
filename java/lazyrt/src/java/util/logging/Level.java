package java.util.logging;

import java.util.HashMap;

/** A severity: a name and an int, where bigger is more severe. */
public class Level implements java.io.Serializable {
    private static final String BUNDLE = "sun.util.logging.resources.logging";
    // Every level ever created, by name and by value, the first creation winning each key; Level.parse
    // resolves against them. Declared first so the constants below can register themselves.
    private static final HashMap<String, Level> byName = new HashMap<String, Level>();
    private static final HashMap<Integer, Level> byValue = new HashMap<Integer, Level>();

    public static final Level OFF = new Level("OFF", Integer.MAX_VALUE, BUNDLE);
    public static final Level SEVERE = new Level("SEVERE", 1000, BUNDLE);
    public static final Level WARNING = new Level("WARNING", 900, BUNDLE);
    public static final Level INFO = new Level("INFO", 800, BUNDLE);
    public static final Level CONFIG = new Level("CONFIG", 700, BUNDLE);
    public static final Level FINE = new Level("FINE", 500, BUNDLE);
    public static final Level FINER = new Level("FINER", 400, BUNDLE);
    public static final Level FINEST = new Level("FINEST", 300, BUNDLE);
    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE, BUNDLE);

    private final String name;
    private final int value;
    private final String bundleName;

    protected Level(String name, int value) {
        this(name, value, null);
    }

    protected Level(String name, int value, String resourceBundleName) {
        if (name == null) {
            throw new NullPointerException();
        }
        this.name = name;
        this.value = value;
        this.bundleName = resourceBundleName;
        synchronized (byName) {
            if (!byName.containsKey(name)) {
                byName.put(name, this);
            }
            if (!byValue.containsKey(value)) {
                byValue.put(value, this);
            }
        }
    }

    public String getResourceBundleName() {
        return bundleName;
    }

    public String getName() {
        return name;
    }

    public String getLocalizedName() {
        return name;
    }

    public final String toString() {
        return name;
    }

    public final int intValue() {
        return value;
    }

    public static Level parse(String name) throws IllegalArgumentException {
        if (name == null) {
            throw new NullPointerException();
        }
        synchronized (byName) {
            Level named = byName.get(name);
            if (named != null) {
                return named;
            }
            int number;
            try {
                number = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad level \"" + name + "\"");
            }
            Level valued = byValue.get(number);
            return valued != null ? valued : new Level(name, number);
        }
    }

    public boolean equals(Object o) {
        return o instanceof Level && ((Level) o).value == value;
    }

    public int hashCode() {
        return value;
    }
}
