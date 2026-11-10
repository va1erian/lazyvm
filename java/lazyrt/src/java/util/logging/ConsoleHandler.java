package java.util.logging;

/** A stream handler on System.err as it was when the handler was made, flushed after every record. */
public class ConsoleHandler extends StreamHandler {
    public ConsoleHandler() {
        super(System.err, new SimpleFormatter());
    }

    public void publish(LogRecord record) {
        super.publish(record);
        flush();
    }

    // The console outlives the handler, so closing only pushes out what is pending.
    public void close() {
        flush();
    }
}
