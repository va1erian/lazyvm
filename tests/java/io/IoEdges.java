package io;

import java.io.*;
import java.util.concurrent.Callable;

/** Edge cases of java.io found by review: which array or offset a call passes on, readers and
 *  streams at their boundaries (zero-byte reads, pending chars, pushback), temporary file names
 *  that would leave their directory, renames onto the same file, and URIs of odd names. */
public class IoEdges {
    static StringBuilder log = new StringBuilder();
    static File root;

    static void t(String label, Callable<Object> c) {
        log.setLength(0);
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        r = r.replace(root.getPath(), "<T>").replace(File.separatorChar, '/').replaceAll("[0-9]{15,}", "N");
        System.out.println(label + ": " + r + (log.length() == 0 ? "" : " log=" + log));
    }

    /** Logs the array identity and range of each write(char[], int, int). */
    static class Arrays extends Writer {
        char[] first;
        public void write(char[] c, int off, int len) {
            if (first == null) first = c;
            log.append("w" + len + (c == first ? "=first;" : "=new;"));
        }
        public void flush() {}
        public void close() {}
    }

    /** n bytes 0,1,2...; logs the offset and length of each bulk read. */
    static class Src extends InputStream {
        int pos, n;
        Src(int n) { this.n = n; }
        public int read() { return pos < n ? pos++ : -1; }
        public int read(byte[] b, int off, int len) {
            log.append("r@" + off + "+" + len + ";");
            int k = Math.min(len, n - pos);
            if (k <= 0) return len == 0 ? 0 : -1;
            for (int i = 0; i < k; i++) b[off + i] = (byte) pos++;
            return k;
        }
    }

    /** Returns its chunks one per read call. */
    static class Chunks extends Reader {
        String[] parts; int i;
        Chunks(String... parts) { this.parts = parts; }
        public int read(char[] c, int off, int len) {
            if (i >= parts.length) return -1;
            String p = parts[i++];
            p.getChars(0, p.length(), c, off);
            return p.length();
        }
        public void close() {}
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        root = File.createTempFile("lazyio", "").getCanonicalFile();
        root.delete();
        root.mkdir();
        writers();
        streams();
        files();
        System.out.println("cleaned " + clean(root));
    }

    static void writers() {
        char[] c = new char[16];
        t("write 1024 reuses the array", () -> { Arrays w = new Arrays(); w.write(repeat('a', 1024)); w.write("bc"); return w.first.length; });
        t("write 1025 takes a new one", () -> { Arrays w = new Arrays(); w.write(repeat('a', 1025)); w.write("bc"); return w.first.length; });
        t("osw write len 0", () -> { Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8"); w.write("abc", 1, 0); return "ok"; });
        t("osw write len -1", () -> { Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8"); w.write("abc", 1, -1); return "ok"; });
        t("osw write off past end len 0", () -> { Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8"); w.write("abc", 9, 0); return "ok"; });
        t("osw closed, bad bounds", () -> { Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8"); w.close(); w.write(c, 10, 10); return "ok"; });
        t("osw closed, good bounds", () -> { Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8"); w.close(); w.write(c, 0, 1); return "ok"; });
        t("br readLine then lf", () -> { BufferedReader r = new BufferedReader(new Chunks("ab\r", "\nxyz")); String l = r.readLine(); int n = r.read(c, 0, 16); return l + " " + n + " " + new String(c, 0, Math.max(n, 0)); });
        t("br small, readLine then lf", () -> { BufferedReader r = new BufferedReader(new Chunks("ab\r", "\nxyz"), 16); String l = r.readLine(); int n = r.read(c, 0, 16); return l + " " + n + " " + new String(c, 0, Math.max(n, 0)); });
    }

    static void streams() {
        byte[] b = new byte[20];
        t("bis read of its size, empty buffer", () -> new BufferedInputStream(new Src(30), 8).read(b, 4, 8));
        t("bis read under its size", () -> new BufferedInputStream(new Src(30), 8).read(b, 4, 7));
        t("isr zero-byte stream", () -> new InputStreamReader(new InputStream() {
            public int read() { return -1; }
            public int read(byte[] x, int off, int len) { return 0; }
        }, "UTF-8").read());
        t("isr closed, pending char by read()", () -> { Reader r = new InputStreamReader(new ByteArrayInputStream("😀z".getBytes("UTF-8")), "UTF-8"); r.read(); r.close(); return r.read(); });
        t("isr closed, pending char by read(char[])", () -> { Reader r = new InputStreamReader(new ByteArrayInputStream("😀z".getBytes("UTF-8")), "UTF-8"); r.read(); r.close(); return r.read(new char[4], 0, 4); });
        t("dis readLine over many CRs", () -> {
            byte[] crs = new byte[60000];
            for (int i = 0; i < crs.length; i += 2) { crs[i] = 'a'; crs[i + 1] = '\r'; }
            @SuppressWarnings("deprecation")
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(crs));
            int lines = 0;
            while (d.readLine() != null) lines++;
            return lines;
        });
        t("dis read len 0 after readLine", () -> { DataInputStream d = new DataInputStream(new Src(10)); d.readLine(); log.setLength(0); return d.read(b, 0, 0); });
        t("dis read after a pushed-back byte", () -> { DataInputStream d = new DataInputStream(new ByteArrayInputStream(new byte[] {'a', '\r', 'b', 'c', 'd'})); String l = d.readLine(); byte[] x = new byte[3]; return l + " " + d.read(x) + " " + new String(x, "ISO-8859-1") + " " + d.read(x, 0, 3); });
    }

    static void files() throws IOException {
        File f = new File(root, "f");
        new FileOutputStream(f).close();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(new byte[] {1, 2, 3, 4, 5}); }
        t("dis skipBytes(-2) then readByte", () -> { try (DataInputStream d = new DataInputStream(new FileInputStream(f))) { return d.skipBytes(-2) + " " + d.readByte(); } });
        t("renameTo itself", () -> f.renameTo(new File(root, "f")) + " " + f.exists());
        t("renameTo other case", () -> f.renameTo(new File(root, "F")) + " " + new File(root, "F").exists());
        t("temp suffix with a separator", () -> tempName(File.createTempFile("abc", "/x", root)));
        t("temp prefix with a separator", () -> tempName(File.createTempFile("d/abc", ".t", root)));
        t("temp suffix climbing out", () -> tempName(File.createTempFile("abc", "/..", root)));
        File d = new File(root, "d");
        d.mkdir();
        t("dir setLastModified", () -> d.setLastModified(1000000000000L) + " " + d.lastModified());
        t("file setLastModified", () -> new File(root, "F").setLastModified(1234567890000L) + " " + new File(root, "F").lastModified());
        t("toURI of the empty path ends with /", () -> new File("").toURI().toString().endsWith("/"));
        File nul = new File(root, "x\u0000y");
        t("NUL char canonical", () -> nul.getCanonicalPath());
        t("NUL char createNewFile", () -> nul.createNewFile());
        t("NUL char stream", () -> new FileInputStream(nul));
        File lone = new File(root, "nope\ud800");
        t("lone surrogate in a message", () -> { try { new FileInputStream(lone).close(); return "opened"; } catch (FileNotFoundException e) { return e.getMessage().startsWith(lone.getPath() + " ("); } });
        t("NUL char queries", () -> nul.exists() + " " + nul.length() + " " + nul.delete() + " " + nul.list());
        t("toURI brackets", () -> { String u = new File(root, "u[v]").toURI().getRawPath(); return u.substring(u.lastIndexOf('/')); });
        t("toURI nbsp and line separator", () -> { String u = new File(root, "a b c").toURI().getRawPath(); return u.substring(u.lastIndexOf('/')); });
        t("lone surrogate names", IoEdges::lone);
    }

    /**
     * Lone surrogates never name the file of U+FFFD. Windows names are UTF-16, so there each
     * lone surrogate is a file of its own that a listing gives back; a Unix file system may
     * refuse such names, which is not checked.
     */
    static String lone() throws IOException {
        File d = new File(root, "lone");
        d.mkdir();
        String[] names = {"x" + (char) 0xD800, "x" + (char) 0xDC00, "x" + (char) 0xFFFD};
        new File(d, names[2]).createNewFile();
        if (new File(d, names[0]).exists() || new File(d, names[1]).exists()) {
            return "aliased";
        }
        if (File.separatorChar == '\\') {
            new File(d, names[0]).createNewFile();
            new File(d, names[1]).createNewFile();
            String[] list = d.list();
            java.util.Arrays.sort(list);
            StringBuilder sb = new StringBuilder();
            for (String n : list) sb.append(Integer.toHexString(n.charAt(1))).append(' ');
            for (String n : names) {
                File f = new File(d, n);
                sb.append(f.getCanonicalFile().getName().equals(n)).append(f.toPath().getFileName().toString().equals(n)).append(' ');
            }
            if (!sb.toString().equals("d800 dc00 fffd truetrue truetrue truetrue ")) {
                return sb.toString();
            }
        }
        return "no aliasing";
    }

    static String tempName(File f) {
        String rest = f.getPath().substring(root.getPath().length() + 1);
        return rest;
    }

    static boolean clean(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File x : fs) clean(x);
        dir.delete();
        return !dir.exists();
    }
}
