package java.util.logging;

/** Turns a record into text for a handler. */
public abstract class Formatter {
    protected Formatter() {
    }

    public abstract String format(LogRecord record);

    public String getHead(Handler h) {
        return "";
    }

    public String getTail(Handler h) {
        return "";
    }

    public String formatMessage(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message == null || params == null || params.length == 0 || !BraceText.hasMarker(message)) {
            return message;
        }
        return BraceText.expand(message, params);
    }
}
