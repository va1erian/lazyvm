package java.util.logging;

/** Where a handler reports failures it cannot throw; this one prints the first report only. */
public class ErrorManager {
    public static final int GENERIC_FAILURE = 0;
    public static final int WRITE_FAILURE = 1;
    public static final int FLUSH_FAILURE = 2;
    public static final int CLOSE_FAILURE = 3;
    public static final int OPEN_FAILURE = 4;
    public static final int FORMAT_FAILURE = 5;

    // Repeating the same broken-handler report on every record would flood stderr.
    private boolean reported;

    public ErrorManager() {
    }

    public synchronized void error(String msg, Exception ex, int code) {
        if (reported) {
            return;
        }
        reported = true;
        System.err.println("java.util.logging.ErrorManager: " + code + (msg != null ? ": " + msg : ""));
        if (ex != null) {
            ex.printStackTrace();
        }
    }
}
