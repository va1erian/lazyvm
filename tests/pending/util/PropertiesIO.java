package util;

import java.io.*;
import java.util.*;

/**
 * Properties: the load syntax, store escaping and layout, list, defaults, name order and the
 * overridable calls. Rules P1-P19 of docs/design/util-text.md. The date line of store is masked.
 */
public class PropertiesIO {
    static final String NL = System.lineSeparator();

    static String show(String t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }

    /** A sorted, escaped view of a Properties table (its own entries only). */
    static String dump(Properties p) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Object, Object> e : new TreeMap<Object, Object>(p).entrySet()) {
            b.append('{').append(show((String) e.getKey())).append('=').append(show((String) e.getValue())).append('}');
        }
        return b.toString();
    }

    static String load(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
        return dump(p);
    }

    /** True when a line has the shape of Date.toString(): "Tue Oct 27 19:30:42 CET 2026". */
    static boolean dateShape(String line) {
        if (line.length() < 26 || line.charAt(0) != '#') return false;
        String d = line.substring(1);
        boolean ok = d.charAt(3) == ' ' && d.charAt(7) == ' ' && d.charAt(10) == ' ' && d.charAt(13) == ':' && d.charAt(16) == ':' && d.charAt(19) == ' ';
        ok &= Character.isLetter(d.charAt(0)) && Character.isLetter(d.charAt(4)) && Character.isDigit(d.charAt(8)) && Character.isDigit(d.charAt(18));
        int n = d.length();
        ok &= d.charAt(n - 5) == ' ';
        for (int i = n - 4; i < n; i++) ok &= Character.isDigit(d.charAt(i));
        return ok;
    }

    /** The stored text, one "/" per line break, with line `dateLine` checked and masked. */
    static String masked(String text, int dateLine) {
        String[] lines = text.split(NL, -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == dateLine) {
                b.append(dateShape(lines[i]) ? "#DATE" : "#BAD[" + show(lines[i]) + "]");
            } else {
                b.append(show(lines[i]));
            }
            b.append(i + 1 < lines.length ? " / " : "");
        }
        return b.toString();
    }

    static String store(Properties p, String comments, int dateLine) throws IOException {
        StringWriter w = new StringWriter();
        p.store(w, comments);
        return masked(w.toString(), dateLine);
    }

    static String storeBytes(Properties p, String comments, int dateLine) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        p.store(o, comments);
        return masked(new String(o.toByteArray(), "ISO-8859-1"), dateLine);
    }

    interface Op { Object run() throws Exception; }

    static String ex(Op r) {
        try {
            return String.valueOf(r.run());
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    static final List<String> calls = new ArrayList<String>();

    /** Logs the overridable calls Properties makes on itself (P17); also exposes `defaults`. */
    static class Spy extends Properties {
        Spy(Properties d) { super(d); }
        Spy() { }
        Properties defaultsField() { return defaults; }
        public synchronized Enumeration<Object> keys() { calls.add("keys"); return super.keys(); }
        public Set<Map.Entry<Object, Object>> entrySet() { calls.add("entrySet"); return super.entrySet(); }
        public Set<Object> keySet() { calls.add("keySet"); return super.keySet(); }
        public synchronized Object get(Object k) { calls.add("get " + k); return super.get(k); }
        public String getProperty(String k) { calls.add("getProperty " + k); return super.getProperty(k); }
        public synchronized Object put(Object k, Object v) { calls.add("put " + k); return super.put(k, v); }
        public synchronized Enumeration<Object> elements() { calls.add("elements"); return super.elements(); }
    }

    /** Hands out its text one char at a time. */
    static class OneChar extends Reader {
        final String text;
        int pos;
        OneChar(String text) { this.text = text; }
        public int read(char[] b, int off, int len) {
            if (pos >= text.length()) return -1;
            b[off] = text.charAt(pos++);
            return 1;
        }
        public void close() {}
    }

    static long seed = 0x9e3779b97f4a7c15L;

    static int rnd(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % n);
    }

    static String randomText(String alpha, int max) {
        StringBuilder b = new StringBuilder();
        int len = rnd(max);
        for (int i = 0; i < len; i++) b.append(alpha.charAt(rnd(alpha.length())));
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        // P1-P6: the load syntax, line by line.
        String[] in = {
            "a=1", "a:1", "a 1", "a\t1", "a\f1", "a = 1", "a  :  1", "a=:1", "a :=1", "a = =1", " \t a=1", "a=1 \t",
            "a", "a=", "=1", ":1", " ", "#c=1\na=1", "!c=1\n a=2", "  # x=1", "\t!x", "a=1\\\n   2", "a=1\\\n", "a=1\\",
            "a=1\\\\\nb=2", "a=1\\\r\n 2\\\r  3", "a\\ b=1", "a\\=b=1", "a\\:b:1", "a\\#b=1", "a=\\t\\n\\r\\f\\\\\\x\\'\\\"\\ ",
            "a=\\u0041\\u00e9\\uD83D\\uDE00\\u00Ff", "a=\\u004", "a=\\u00G1", "a=\\uzzzz", "a=\\u", "\\u0061=1", "\\u003d=1",
            "a=1\rb=2\r\nc=3\n\nd=4", "a=1\nb", "#a\\\nb=1", "a=1\\\n# not comment\nc=3", "a\\\n b=1", "é=€",
            "a=1\na=2", "a=\\", "a\\", "a=1\\\n\\\n x", "k=v\\ \\ ", "\\ k=1", "\\\\=1", "x\\\r\ny=1", "a=1\u0000b",
            "a b=1", "a\u000bb=1", "\n\n\n", "\\\n", "a=b\\\n\n c=d", "#\\u12", "a\\u0020b=c", "a\\\n\\\n=1", "\r\n\ra=1\r",
        };
        for (String t : in) System.out.println("load [" + show(t) + "] " + load(t));
        // P7: load(InputStream) reads ISO 8859-1 bytes.
        Properties p = new Properties();
        p.load(new ByteArrayInputStream("a=é\\u20ac\nÿ=x\nb=Ã©".getBytes("ISO-8859-1")));
        System.out.println("load stream " + dump(p));
        // P8: load adds to what is there; a reader handing out one char at a time gives the same table.
        Properties merged = new Properties();
        merged.setProperty("pre", "1");
        merged.setProperty("keep", "k");
        merged.load(new StringReader("pre=2\nnew=3"));
        System.out.println("load merges " + dump(merged));
        // P9-P12: store escaping, Writer and OutputStream, comments, the date line.
        Properties st = new Properties();
        st.setProperty(" lead", " v lead");
        st.setProperty("a=b:c", "x=y:z");
        st.setProperty("#h", "#v");
        st.setProperty("!h", "!v");
        st.setProperty("in#side", "in!side");
        st.setProperty("tab\tnl\ncr\rff\f", "tab\tnl\ncr\rff\f");
        st.setProperty("unié€\u0001\u007f", "unié€\u0001\u007f😀\u0000");
        st.setProperty("sp ace", "sp ace  ");
        st.setProperty("back\\slash", "back\\slash");
        st.setProperty("", "");
        st.setProperty("abÿ", "");
        st.setProperty("k", "  two lead");
        st.setProperty("k2", "\ttab");
        System.out.println("store writer " + store(st, null, 0));
        System.out.println("store stream " + storeBytes(st, "cé€", 1));
        System.out.println("comment writer " + store(new Properties(), "line1\nline2\r\nline3\rline4 #! é€\u0001 \\ end", 4));
        System.out.println("comment stream " + storeBytes(new Properties(), "line1\nlé€\u0001😀", 2));
        System.out.println("comment breaks " + store(new Properties(), "\n#x\n!y\n\nz\r", 6));
        System.out.println("comment empty " + store(new Properties(), "", 1) + " | lead " + store(new Properties(), "#already", 1) + " | " + store(new Properties(), "!bang", 1) + " | " + store(new Properties(), "\r\n\r\n", 3));
        // P13: store visits keys() of the table and writes them in that order (Hashtable order).
        Properties big = new Properties();
        for (int i = 0; i < 30; i++) big.setProperty("key" + i, "v" + i);
        System.out.println("store order " + store(big, null, 0));
        Properties sorted = new Properties() {
            public synchronized Enumeration<Object> keys() { return Collections.enumeration(new TreeSet<Object>(super.keySet())); }
        };
        sorted.putAll(big);
        System.out.println("store keys() override " + store(sorted, null, 0));
        // P14: non-String keys or values.
        System.out.println("store non-string " + ex(() -> { Properties x = new Properties(); x.put("a", 1); x.store(new StringWriter(), null); return "ok"; }) + " | " + ex(() -> { Properties x = new Properties(); x.put(1, "a"); x.store(new StringWriter(), null); return "ok"; }));
        // P15: defaults: getProperty, propertyNames, stringPropertyNames.
        Properties d2 = new Properties();
        d2.setProperty("z", "dz");
        d2.setProperty("c", "dc");
        Properties d1 = new Properties(d2);
        d1.setProperty("b", "db");
        d1.setProperty("c", "d1c");
        final Properties pd = new Properties(d1);
        pd.setProperty("a", "pa");
        pd.put("obj", 5);
        System.out.println("get " + pd.getProperty("a") + " " + pd.getProperty("b") + " " + pd.getProperty("c") + " " + pd.getProperty("z") + " " + pd.getProperty("q") + " " + pd.getProperty("q", "def") + " " + pd.getProperty("obj") + " " + pd.getProperty("obj", "d") + " " + pd.get("b") + " " + pd.size() + " " + pd.setProperty("a", "pa2") + " " + pd.setProperty("new", "n"));
        System.out.println("names " + pd.stringPropertyNames() + " " + Collections.list(pd.propertyNames()) + " " + ex(() -> pd.stringPropertyNames().add("x")));
        pd.put(7, "seven");
        System.out.println("names non-string " + pd.stringPropertyNames() + " | " + ex(() -> Collections.list(pd.propertyNames())));
        d1.put("dobj", 1.5);
        System.out.println("names default non-string " + pd.stringPropertyNames() + " | " + ex(() -> Collections.list(pd.propertyNames())));
        // P16: list(): header, values over 40 chars cut to 37 plus "...", keys never cut, defaults included.
        Properties l = new Properties(d2);
        l.setProperty("short", "v");
        l.setProperty("forty", "0123456789012345678901234567890123456789");
        l.setProperty("fortyone", "01234567890123456789012345678901234567890");
        l.setProperty("k0123456789012345678901234567890123456789", "long key");
        l.setProperty("z", "own z");
        StringWriter sw = new StringWriter();
        l.list(new PrintWriter(sw, true));
        System.out.println("list writer " + show(sw.toString().replace(NL, "|")));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        l.list(new PrintStream(bo, true, "UTF-8"));
        System.out.println("list stream " + show(new String(bo.toByteArray(), "UTF-8").replace(NL, "|")));
        System.out.println("list non-string " + ex(() -> { Properties x = new Properties(); x.put(3, "x"); x.list(new PrintWriter(new StringWriter())); return "ok"; }) + " | " + ex(() -> { Properties x = new Properties(); x.put("x", 3); x.list(new PrintWriter(new StringWriter())); return "ok"; }));
        // P18: argument checks.
        System.out.println("nulls " + ex(() -> { new Properties().load((Reader) null); return "ok"; }) + " | " + ex(() -> { new Properties().load((InputStream) null); return "ok"; }) + " | " + ex(() -> { new Properties().store((Writer) null, null); return "ok"; }) + " | " + ex(() -> { new Properties().store((OutputStream) null, "c"); return "ok"; }) + " | " + ex(() -> new Properties().setProperty(null, "a")) + " | " + ex(() -> new Properties().setProperty("a", null)) + " | " + ex(() -> new Properties().getProperty(null)) + " | " + ex(() -> new Properties((Properties) null).getProperty("x")) + " | " + ex(() -> { new Properties().list((PrintStream) null); return "ok"; }) + " | " + ex(() -> { Properties x = new Properties(); x.setProperty("a", "1"); x.list((PrintWriter) null); return "ok"; }));
        // P17: which overridable methods load, store, list and the name methods call.
        calls.clear();
        Spy spy = new Spy(d2);
        spy.setProperty("x", "1");
        spy.setProperty("y", "2");
        System.out.println("spy setProperty " + calls);
        calls.clear();
        spy.store(new StringWriter(), "c");
        System.out.println("spy store " + calls);
        calls.clear();
        spy.list(new PrintWriter(new StringWriter()));
        System.out.println("spy list " + calls);
        calls.clear();
        spy.stringPropertyNames();
        System.out.println("spy stringPropertyNames " + calls);
        calls.clear();
        spy.propertyNames();
        System.out.println("spy propertyNames " + calls);
        calls.clear();
        spy.getProperty("z");
        spy.getProperty("zz", "d");
        System.out.println("spy getProperty " + calls);
        calls.clear();
        spy.load(new StringReader("m=1\nn=2\nm=3"));
        System.out.println("spy load " + calls);
        calls.clear();
        Spy outer = new Spy(spy);
        outer.getProperty("y");
        outer.getProperty("nope");
        System.out.println("spy defaults chain " + calls);
        calls.clear();
        outer.setProperty("w", "3");
        calls.clear();
        outer.list(new PrintWriter(new StringWriter()));
        System.out.println("spy defaults list " + calls);
        calls.clear();
        outer.stringPropertyNames();
        System.out.println("spy defaults names " + calls);
        // P15: an own value that is not a String falls through to the defaults.
        Properties dd = new Properties();
        dd.setProperty("o", "from defaults");
        Properties od = new Properties(dd);
        od.put("o", 5);
        od.put("p", 6);
        System.out.println("non-string own value " + od.getProperty("o") + " " + od.getProperty("p") + " " + od.getProperty("p", "dflt") + " " + od.stringPropertyNames());
        // P12: store through a BufferedWriter ends lines with its newLine().
        calls.clear();
        BufferedWriter bw = new BufferedWriter(new StringWriter()) {
            public void newLine() throws IOException { calls.add("newLine"); super.newLine(); }
        };
        Properties two = new Properties();
        two.setProperty("a", "1");
        two.setProperty("b", "2");
        two.store(bw, "x\ny");
        System.out.println("store BufferedWriter newLine calls " + calls.size());
        // P19: name order with defaults (a fresh Hashtable filled deepest defaults first), fuzzed.
        long nameHash = 0;
        for (int t = 0; t < 400; t++) {
            Spy q = null;
            int levels = 1 + rnd(3);
            for (int lv = 0; lv < levels; lv++) {
                Spy next = q == null ? new Spy() : new Spy(q);
                int cnt = rnd(25);
                for (int i = 0; i < cnt; i++) next.setProperty("k" + rnd(60), "v" + i);
                q = next;
            }
            calls.clear();
            StringWriter lw = new StringWriter();
            q.list(new PrintWriter(lw, true));
            String names = q.stringPropertyNames() + " " + Collections.list(q.propertyNames()) + " " + lw.toString().replace(NL, "|");
            nameHash = nameHash * 31 + names.hashCode();
            if (t < 3) System.out.println("names fuzz " + names);
        }
        System.out.println("names fuzz hash " + nameHash);
        // P1-P8 fuzz: random load texts; a one-char reader gives the same table.
        String alpha = "ab=: \\\n\r#!\tu0é";
        long loadHash = 0;
        int sameChunked = 0;
        for (int t = 0; t < 3000; t++) {
            String text = randomText(alpha, 20);
            String r1 = load(text);
            Properties c = new Properties();
            String r2;
            try {
                c.load(new OneChar(text));
                r2 = dump(c);
            } catch (Exception e) {
                r2 = e.getClass().getName() + ": " + e.getMessage();
            }
            if (r1.equals(r2)) sameChunked++;
            loadHash = loadHash * 31 + r1.hashCode();
            if (t % 300 == 0) System.out.println("load fuzz [" + show(text) + "] " + r1);
        }
        System.out.println("load fuzz hash " + loadHash + " chunked same " + sameChunked);
        // P9-P11 fuzz: random tables survive store then load, through a Writer and a stream.
        String kv = "a =:#!\\\t\n\r\fé€\u0001\ud83d";
        long storeHash = 0;
        int roundW = 0, roundS = 0;
        for (int t = 0; t < 600; t++) {
            Properties r = new Properties();
            int n = rnd(4);
            for (int i = 0; i < n; i++) r.setProperty(randomText(kv, 6), randomText(kv, 6));
            StringWriter w = new StringWriter();
            r.store(w, null);
            String text = w.toString();
            String body = text.substring(text.indexOf(NL) + NL.length());
            storeHash = storeHash * 31 + body.hashCode();
            Properties back = new Properties();
            back.load(new StringReader(text));
            if (back.equals(r)) roundW++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            r.store(o, null);
            byte[] bytes = o.toByteArray();
            String sbody = new String(bytes, "ISO-8859-1");
            storeHash = storeHash * 31 + sbody.substring(sbody.indexOf(NL) + NL.length()).hashCode();
            Properties backS = new Properties();
            backS.load(new ByteArrayInputStream(bytes));
            if (backS.equals(r)) roundS++;
            if (t % 100 == 0) System.out.println("store fuzz " + show(body.replace(NL, "|")));
        }
        System.out.println("store fuzz hash " + storeHash + " round trips " + roundW + " " + roundS);
        System.out.println("done");
    }
}
