package java.util.logging;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/** A destination for records. */
public abstract class Handler {
    private volatile Level level = Level.ALL;
    private volatile Formatter formatter;
    private volatile Filter filter;
    private volatile String encoding;
    private volatile ErrorManager errors = new ErrorManager();

    protected Handler() {
    }

    public abstract void publish(LogRecord record);

    public abstract void flush();

    public abstract void close() throws SecurityException;

    public void setFormatter(Formatter newFormatter) throws SecurityException {
        if (newFormatter == null) {
            throw new NullPointerException();
        }
        formatter = newFormatter;
    }

    public Formatter getFormatter() {
        return formatter;
    }

    public void setEncoding(String encoding) throws SecurityException, UnsupportedEncodingException {
        if (encoding != null && !supported(encoding)) {
            throw new UnsupportedEncodingException(encoding);
        }
        this.encoding = encoding;
    }

    // Charset.isSupported rejects a malformed name by throwing; to the caller that is just "unknown".
    private static boolean supported(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getEncoding() {
        return encoding;
    }

    public void setFilter(Filter newFilter) throws SecurityException {
        filter = newFilter;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setErrorManager(ErrorManager em) {
        if (em == null) {
            throw new NullPointerException();
        }
        errors = em;
    }

    public ErrorManager getErrorManager() {
        return errors;
    }

    protected void reportError(String msg, Exception ex, int code) {
        try {
            errors.error(msg, ex, code);
        } catch (Exception e) {
            // A failing error manager must not turn a reported failure into a thrown one.
            System.err.println("Handler.reportError caught:");
            e.printStackTrace();
        }
    }

    public void setLevel(Level newLevel) throws SecurityException {
        if (newLevel == null) {
            throw new NullPointerException();
        }
        level = newLevel;
    }

    public Level getLevel() {
        return level;
    }

    public boolean isLoggable(LogRecord record) {
        if (record == null) {
            throw new NullPointerException();
        }
        int limit = level.intValue();
        if (record.getLevel().intValue() < limit || limit == Level.OFF.intValue()) {
            return false;
        }
        Filter f = filter;
        return f == null || f.isLoggable(record);
    }
}
