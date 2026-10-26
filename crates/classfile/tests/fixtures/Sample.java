import java.io.Serializable;

/** Fixture for the class file parser tests; regenerate with tools/fixtures.py. */
public class Sample implements Serializable, Runnable {
    static final int ANSWER = 42;
    static final long BIG = 1L << 40;
    static final double PI = 3.14159;
    static final String NAME = "sémple\0";
    private int count;

    public void run() {
        try {
            count += ANSWER;
        } catch (RuntimeException e) {
            count = -1;
        }
        Runnable r = () -> count++;
        r.run();
    }

    public static native int nativeOne();
}
