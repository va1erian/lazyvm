package java.util.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

/** Writes formatted records to an output stream through one encoder, which holds a bounded buffer. */
public class StreamHandler extends Handler {
    // One writer per attached stream: its encoder keeps state across records (a byte-order mark is written
    // once, a surrogate pair may be split between records), and a failed write drops what it held.
    private Writer writer;
    private OutputStream output;
    private boolean headWritten;

    public StreamHandler() {
        setLevel(Level.INFO);
        setFormatter(new SimpleFormatter());
    }

    public StreamHandler(OutputStream out, Formatter formatter) {
        this();
        if (out == null || formatter == null) {
            throw new NullPointerException();
        }
        setFormatter(formatter);
        try {
            setEncoding(null);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // null always means the default
        }
        setOutputStream(out);
    }

    protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
        if (out == null) {
            throw new NullPointerException();
        }
        finish();
        attach(out);
    }

    private void attach(OutputStream out) {
        headWritten = false;
        output = out;
        try {
            writer = open(out);
        } catch (UnsupportedEncodingException e) {
            // setEncoding only stores names the runtime knows, so this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    private Writer open(OutputStream out) throws UnsupportedEncodingException {
        String encoding = getEncoding();
        return encoding == null ? new OutputStreamWriter(out) : new OutputStreamWriter(out, encoding);
    }

    // The old encoder is flushed into the stream once, then replaced by one for the new encoding.
    public synchronized void setEncoding(String encoding) throws SecurityException, UnsupportedEncodingException {
        super.setEncoding(encoding);
        if (writer == null) {
            return;
        }
        flush();
        writer = open(output);
    }

    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String text;
        try {
            text = getFormatter().format(record);
        } catch (Exception e) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            writeHead();
            writer.write(text);
        } catch (Exception e) {
            reportError(null, e, ErrorManager.WRITE_FAILURE);
        }
    }

    public synchronized boolean isLoggable(LogRecord record) {
        return writer != null && record != null && super.isLoggable(record);
    }

    public synchronized void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (Exception e) {
            reportError(null, e, ErrorManager.FLUSH_FAILURE);
        }
    }

    public synchronized void close() throws SecurityException {
        finish();
    }

    // Writes the head if nothing was written, then the tail, and closes the writer and with it the stream.
    // Whatever goes wrong, the stream is let go.
    private void finish() {
        if (writer == null) {
            return;
        }
        try {
            writeHead();
            writer.write(getFormatter().getTail(this));
            writer.flush();
            writer.close();
        } catch (Exception e) {
            reportError(null, e, ErrorManager.CLOSE_FAILURE);
        }
        writer = null;
    }

    // The head counts as written only once it was, so a head that fails is tried again on the next record.
    private void writeHead() throws IOException {
        if (!headWritten) {
            writer.write(getFormatter().getHead(this));
            headWritten = true;
        }
    }
}
