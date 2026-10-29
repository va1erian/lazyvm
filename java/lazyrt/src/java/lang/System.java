package java.lang;

import java.io.InputStream;
import java.io.PrintStream;
import lazy.internal.ConsoleInputStream;
import lazy.internal.ConsoleOutputStream;
import lazy.internal.VM;

public final class System {
    public static final InputStream in;
    public static final PrintStream out;
    public static final PrintStream err;

    private static String lineSeparator;

    static {
        // Spec I1: stdin is a BufferedInputStream, so it supports mark and reset.
        in = new java.io.BufferedInputStream(new ConsoleInputStream(0));
        out = new PrintStream(new ConsoleOutputStream(1), true);
        err = new PrintStream(new ConsoleOutputStream(2), true);
    }

    private System() {
    }

    public static native void arraycopy(Object src, int srcPos, Object dest, int destPos,
            int length);

    public static native long currentTimeMillis();

    public static native long nanoTime();

    public static native int identityHashCode(Object x);

    public static String getProperty(String key) {
        if (key == null) {
            throw new NullPointerException("key can't be null");
        }
        if (key.length() == 0) {
            throw new IllegalArgumentException("key can't be empty");
        }
        return VM.getProperty(key);
    }

    public static String getProperty(String key, String def) {
        String value = getProperty(key);
        return value == null ? def : value;
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
}
