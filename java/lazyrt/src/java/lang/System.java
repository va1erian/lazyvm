package java.lang;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Properties;
import lazy.internal.ConsoleInputStream;
import lazy.internal.ConsoleOutputStream;
import lazy.internal.VM;

public final class System {
    public static final InputStream in;
    public static final PrintStream out;
    public static final PrintStream err;

    private static String lineSeparator;
    /**
     * The properties once a program changes them or asks for the table. Until
     * then they are the set the VM started with, and no table need be loaded.
     */
    private static volatile Properties props;

    static {
        // Spec I1: stdin is a BufferedInputStream, so it supports mark and reset.
        in = new java.io.BufferedInputStream(new ConsoleInputStream(0));
        out = new PrintStream(new ConsoleOutputStream(1), true);
        err = new PrintStream(new ConsoleOutputStream(2), true);
        // Fixes the default locales before any user code can change the user.* properties.
        java.util.Locale.getDefault();
    }

    private System() {
    }

    public static native void arraycopy(Object src, int srcPos, Object dest, int destPos,
            int length);

    public static native long currentTimeMillis();

    public static native long nanoTime();

    public static native int identityHashCode(Object x);

    public static String getProperty(String key) {
        checkKey(key);
        Properties p = props;
        return p != null ? p.getProperty(key) : VM.getProperty(key);
    }

    public static String getProperty(String key, String def) {
        String value = getProperty(key);
        return value == null ? def : value;
    }

    public static String setProperty(String key, String value) {
        checkKey(key);
        if (value == null) {
            throw new NullPointerException();
        }
        // The cast fails on a value that is not a String, as on the oracle.
        return (String) getProperties().setProperty(key, value);
    }

    public static String clearProperty(String key) {
        checkKey(key);
        return (String) getProperties().remove(key);
    }

    public static Properties getProperties() {
        Properties p = props;
        return p != null ? p : firstProperties();
    }

    private static synchronized Properties firstProperties() {
        if (props == null) {
            props = startingProperties();
        }
        return props;
    }

    public static void setProperties(Properties p) {
        props = p != null ? p : startingProperties();
    }

    private static Properties startingProperties() {
        Properties p = new Properties();
        String[] pairs = VM.properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    private static void checkKey(String key) {
        if (key == null) {
            throw new NullPointerException("key can't be null");
        }
        if (key.length() == 0) {
            throw new IllegalArgumentException("key can't be empty");
        }
    }

    public static String getenv(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        return lazy.internal.Environment.lookup(name);
    }

    public static java.util.Map<String, String> getenv() {
        return lazy.internal.Environment.map();
    }

    public static void setIn(InputStream in) {
        setIn0(in);
    }

    public static void setOut(PrintStream out) {
        setOut0(out);
    }

    public static void setErr(PrintStream err) {
        setErr0(err);
    }

    // The fields are final for javac, so only the VM can store to them.
    private static native void setIn0(InputStream in);

    private static native void setOut0(PrintStream out);

    private static native void setErr0(PrintStream err);

    public static String lineSeparator() {
        if (lineSeparator == null) {
            String s = VM.getProperty("line.separator");
            lineSeparator = s == null ? "\n" : s;
        }
        return lineSeparator;
    }

    public static void exit(int status) {
        Runtime.getRuntime().exit(status);
    }

    public static void gc() {
        Runtime.getRuntime().gc();
    }

    public static void runFinalization() {
        Runtime.getRuntime().runFinalization();
    }
}
