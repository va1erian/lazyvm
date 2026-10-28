package java.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * A string table with a chain of defaults, read and written in the line format of
 * docs/design/util-text.md section 2 (rules P1-P20). The format is PropertiesLoader and PropertiesText.
 */
public class Properties extends Hashtable<Object, Object> {
    private static final long serialVersionUID = 4112578634029874840L;

    /** Searched by getProperty when this table has no String value for a key. */
    protected Properties defaults;

    public Properties() {
        this(null);
    }

    public Properties(Properties defaults) {
        this.defaults = defaults;
    }

    /** P15, P17: one overridable put. */
    public synchronized Object setProperty(String key, String value) {
        return put(key, value);
    }

    /** P1-P6, P8. */
    public synchronized void load(Reader reader) throws IOException {
        PropertiesLoader.load(Objects.requireNonNull(reader), this);
    }

    /** P7: each byte is one char (ISO 8859-1). */
    public synchronized void load(InputStream inStream) throws IOException {
        Objects.requireNonNull(inStream);
        PropertiesLoader.load(new InputStreamReader(inStream, StandardCharsets.ISO_8859_1), this);
    }

    /** The old form of store, which drops I/O errors. */
    @Deprecated
    public void save(OutputStream out, String comments) {
        try {
            store(out, comments);
        } catch (IOException e) {
            // The deprecated contract: errors are not reported.
        }
    }

    /** P9-P14 through a Writer: chars outside ASCII are written as they are. */
    public void store(Writer writer, String comments) throws IOException {
        BufferedWriter out = writer instanceof BufferedWriter ? (BufferedWriter) writer : new BufferedWriter(writer);
        storeTo(out, comments, false);
    }

    /** P9-P14 through a stream: ISO 8859-1 bytes, chars outside printable ASCII escaped. */
    public void store(OutputStream out, String comments) throws IOException {
        Writer w = new OutputStreamWriter(Objects.requireNonNull(out), StandardCharsets.ISO_8859_1);
        storeTo(new BufferedWriter(w), comments, true);
    }

    /** P11-P13: comments, the date line, then one line per key of keys(). */
    private void storeTo(BufferedWriter out, String comments, boolean ascii) throws IOException {
        if (comments != null) {
            PropertiesText.writeComments(out, comments);
        }
        out.write("#" + new Date().toString());
        out.newLine();
        synchronized (this) {
            for (Enumeration<Object> e = keys(); e.hasMoreElements();) {
                String key = (String) e.nextElement();
                String value = (String) get(key);
                out.write(PropertiesText.escape(key, true, ascii));
                out.write('=');
                out.write(PropertiesText.escape(value, false, ascii));
                out.newLine();
            }
        }
        out.flush();
    }

    /** XML needs a parser LazyVM does not have yet (util-text.md section 4). */
    public synchronized void loadFromXML(InputStream in) throws IOException {
        throw new UnsupportedOperationException("Properties XML is not supported by LazyVM");
    }

    public void storeToXML(OutputStream os, String comment) throws IOException {
        throw new UnsupportedOperationException("Properties XML is not supported by LazyVM");
    }

    public void storeToXML(OutputStream os, String comment, String encoding) throws IOException {
        throw new UnsupportedOperationException("Properties XML is not supported by LazyVM");
    }

    /** P15: this table's String value, else the defaults' answer. */
    public String getProperty(String key) {
        Object own = super.get(key);
        if (own instanceof String) {
            return (String) own;
        }
        return defaults == null ? null : defaults.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        String v = getProperty(key);
        return v == null ? defaultValue : v;
    }

    /** P15: every String key, values of any type; a non-String key anywhere throws. */
    public Enumeration<?> propertyNames() {
        Hashtable<String, Object> h = new Hashtable<String, Object>();
        collect(h, false, false);
        return h.keys();
    }

    /** P15: keys whose key and value are both Strings, found anywhere in the chain. */
    public Set<String> stringPropertyNames() {
        Hashtable<String, Object> h = new Hashtable<String, Object>();
        collect(h, true, false);
        return Collections.unmodifiableSet(h.keySet());
    }

    /** P16: values over 40 chars are cut to 37 plus "...". */
    public void list(PrintStream out) {
        out.println("-- listing properties --");
        for (Map.Entry<String, Object> e : listed().entrySet()) {
            out.println(e.getKey() + "=" + cut((String) e.getValue()));
        }
    }

    public void list(PrintWriter out) {
        out.println("-- listing properties --");
        for (Map.Entry<String, Object> e : listed().entrySet()) {
            out.println(e.getKey() + "=" + cut((String) e.getValue()));
        }
    }

    private Hashtable<String, Object> listed() {
        Hashtable<String, Object> h = new Hashtable<String, Object>();
        collect(h, false, true);
        return h;
    }

    private static String cut(String v) {
        return v.length() > 40 ? v.substring(0, 37) + "..." : v;
    }

    /**
     * P17, P19: the deepest defaults first, then this table through its own keys() and get. With
     * `onlyStrings`, pairs that are not two Strings are skipped; otherwise a non-String key, or a
     * non-String value when `castValues`, throws ClassCastException.
     */
    private synchronized void collect(Hashtable<String, Object> h, boolean onlyStrings, boolean castValues) {
        if (defaults != null) {
            defaults.collect(h, onlyStrings, castValues);
        }
        for (Enumeration<Object> e = keys(); e.hasMoreElements();) {
            Object k = e.nextElement();
            if (onlyStrings) {
                Object v = get(k);
                if (k instanceof String && v instanceof String) {
                    h.put((String) k, v);
                }
            } else {
                String key = (String) k;
                Object v = get(key);
                h.put(key, castValues ? (String) v : v);
            }
        }
    }
}
