package io;

import java.io.*;
import java.util.concurrent.Callable;

/** FileInputStream, FileOutputStream (append), FileReader/FileWriter and RandomAccessFile in a fresh
 *  temporary directory. OS error text differs by platform, so an IOException prints its class and
 *  whether its message has the shape "<path> (<reason>)". */
public class FileStreams {
    static File root;

    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + ": " + shape(e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    /** For errors whose whole message is OS text: the class only. */
    static void tc(String label, Callable<Object> c) {
        try {
            System.out.println(label + ": " + c.call());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName());
        }
    }

    /** OS messages are "<path> (<reason>)"; print the path relative to root and hide the reason. */
    static String shape(String m) {
        if (m == null) return "null";
        String p = root.getPath();
        if (m.startsWith(p) && m.endsWith(")") && m.indexOf(" (", p.length()) >= 0) {
            int i = m.indexOf(" (", p.length());
            return "<tmp>" + m.substring(p.length(), i).replace(File.separatorChar, '/') + " (<os reason>)";
        }
        return m;
    }

    static String content(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) sb.append((char) c);
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        root = File.createTempFile("lazyio", ".d");
        root.delete();
        root.mkdir();
        File f = new File(root, "f");
        File missing = new File(root, "missing");
        t("fis missing", () -> new FileInputStream(missing));
        t("fis missing dir", () -> new FileInputStream(new File(root, "nope/x")));
        t("fis on dir", () -> new FileInputStream(root));
        t("fos on dir", () -> new FileOutputStream(root));
        t("fos in missing dir", () -> new FileOutputStream(new File(root, "nope/x")));
        t("fis null", () -> new FileInputStream((String) null));
        t("fis null File", () -> new FileInputStream((File) null));
        t("fos write", () -> { try (FileOutputStream o = new FileOutputStream(f)) { o.write("hello".getBytes("UTF-8")); o.write(0x121); } return content(f); });
        t("fos truncates", () -> { try (FileOutputStream o = new FileOutputStream(f.getPath())) { o.write('X'); } return content(f); });
        t("fos append", () -> { try (FileOutputStream o = new FileOutputStream(f, true)) { o.write("yz".getBytes()); } return content(f); });
        t("fos append by name", () -> { try (FileOutputStream o = new FileOutputStream(f.getPath(), true)) { o.write("!".getBytes(), 0, 1); } return content(f) + " " + f.length(); });
        t("fos append creates", () -> { File g = new File(root, "g"); try (FileOutputStream o = new FileOutputStream(g, true)) { o.write('1'); } return content(g); });
        t("fos write after close", () -> { FileOutputStream o = new FileOutputStream(f, true); o.close(); o.close(); o.write(1); return "?"; });
        t("fos bounds", () -> { try (FileOutputStream o = new FileOutputStream(new File(root, "h"))) { o.write(new byte[2], 1, 2); } return "?"; });
        t("fos flush", () -> { try (FileOutputStream o = new FileOutputStream(new File(root, "h"))) { o.write(65); o.flush(); return new File(root, "h").length(); } });
        try (FileOutputStream o = new FileOutputStream(f)) { for (int i = 0; i < 100; i++) o.write(i); }
        t("fis read/available/skip", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[10];
                return in.available() + " " + in.read() + " " + in.read(b) + " " + b[9] + " " + in.available() + " " + in.skip(50) + " " + in.read() + " " + in.available();
            }
        });
        t("fis skip past end", () -> { try (FileInputStream in = new FileInputStream(f)) { return in.skip(1000) + " " + in.available() + " " + in.read() + " " + in.read(new byte[4]); } });
        t("fis skip negative", () -> { try (FileInputStream in = new FileInputStream(f)) { in.skip(10); return in.skip(-4) + " " + in.read(); } });
        tc("fis skip negative at 0", () -> { try (FileInputStream in = new FileInputStream(f)) { return in.skip(-4); } });
        t("fis read len 0", () -> { try (FileInputStream in = new FileInputStream(f)) { in.skip(100); return in.read(new byte[4], 0, 0) + " " + in.read(new byte[4], 0, 4); } });
        t("fis read big", () -> { try (FileInputStream in = new FileInputStream(f)) { return in.read(new byte[1000]) + " " + in.read(new byte[1000]); } });
        t("fis bounds", () -> { try (FileInputStream in = new FileInputStream(f)) { return in.read(new byte[4], 3, 2); } });
        t("fis mark", () -> { try (FileInputStream in = new FileInputStream(f)) { in.mark(4); return in.markSupported(); } });
        t("fis reset", () -> { try (FileInputStream in = new FileInputStream(f)) { in.reset(); return "?"; } });
        t("fis read after close", () -> { FileInputStream in = new FileInputStream(f); in.close(); in.close(); return in.read(); });
        t("fis available after close", () -> { FileInputStream in = new FileInputStream(f); in.close(); return in.available(); });
        t("fis skip after close", () -> { FileInputStream in = new FileInputStream(f); in.close(); return in.skip(1); });
        t("fis empty file", () -> { File e = new File(root, "e"); new FileOutputStream(e).close(); try (FileInputStream in = new FileInputStream(e)) { return in.available() + " " + in.read() + " " + in.read(new byte[3]); } });

        t("FileWriter/FileReader", () -> {
            File w = new File(root, "w.txt");
            try (FileWriter fw = new FileWriter(w)) { fw.write("héllo"); }
            try (FileWriter fw = new FileWriter(w, true)) { fw.write("!"); }
            char[] c = new char[20];
            try (FileReader fr = new FileReader(w)) { int n = fr.read(c); return n + " " + new String(c, 0, n) + " " + w.length(); }
        });
        t("FileReader missing", () -> new FileReader(missing));

        File r = new File(root, "raf");
        t("raf r missing", () -> new RandomAccessFile(missing, "r"));
        t("raf bad mode", () -> new RandomAccessFile(r, "w"));
        t("raf bad mode rwx", () -> new RandomAccessFile(r, "rwx"));
        t("raf on dir", () -> new RandomAccessFile(root, "r"));
        for (String mode : new String[]{"rw", "rws", "rwd"}) {
            File m = new File(root, "raf-" + mode);
            t("raf " + mode, () -> {
                try (RandomAccessFile a = new RandomAccessFile(m, mode)) {
                    a.write(new byte[]{1, 2, 3});
                    return a.length() + " " + a.getFilePointer();
                }
            });
        }
        t("raf rw keeps content", () -> { try (RandomAccessFile a = new RandomAccessFile(new File(root, "raf-rw"), "rw")) { return a.length() + " " + a.read(); } });
        t("raf primitives", () -> {
            try (RandomAccessFile a = new RandomAccessFile(r, "rw")) {
                a.writeInt(0x01020304); a.writeLong(-2); a.writeShort(0x8001); a.writeChar(0x20ac); a.writeBoolean(true); a.writeByte(0x1ff);
                a.writeFloat(1.5f); a.writeDouble(-0.25); a.writeUTF("hé"); a.writeChars("ab"); a.writeBytes("Łz");
                long len = a.length();
                a.seek(0);
                return len + " " + Integer.toHexString(a.readInt()) + " " + a.readLong() + " " + a.readShort() + " " + (int) a.readChar() + " " + a.readBoolean() + " " + a.readByte()
                    + " " + a.readFloat() + " " + a.readDouble() + " " + a.readUTF() + " " + a.readChar() + a.readChar() + " " + a.readUnsignedByte() + " " + a.read() + " " + a.read();
            }
        });
        t("raf seek past end", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(100); long l1 = a.length(); int x = a.read(); a.write(7); return l1 + " " + x + " " + a.length() + " " + a.getFilePointer(); } });
        t("raf gap is zero", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(60); return a.read() + " " + a.readByte(); } });
        t("raf seek -1", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(-1); return "?"; } });
        t("raf setLength shrink", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(90); a.setLength(10); return a.length() + " " + a.getFilePointer() + " " + a.read(); } });
        t("raf setLength grow", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(5); a.setLength(20); return a.length() + " " + a.getFilePointer(); } });
        tc("raf setLength -1", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.setLength(-1); return "?"; } });
        t("raf readInt at eof", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(18); return a.readInt(); } });
        t("raf pointer after short readInt", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(18); try { a.readInt(); } catch (EOFException e) { } return a.getFilePointer(); } });
        t("raf readFully at eof", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(17); a.readFully(new byte[5]); return "?"; } });
        t("raf skipBytes", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "rw")) { a.seek(15); return a.skipBytes(3) + " " + a.skipBytes(10) + " " + a.skipBytes(-1) + " " + a.getFilePointer(); } });
        t("raf readLine", () -> {
            try (RandomAccessFile a = new RandomAccessFile(r, "rw")) {
                a.setLength(0); a.write(new byte[]{'a', '\r', '\n', 'b', '\r', 'c', '\n', '\n', (byte) 0xe9, 'd'});
                a.seek(0);
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = a.readLine()) != null) sb.append('[').append(l).append(']');
                return sb + " " + (int) sb.charAt(sb.length() - 3);
            }
        });
        tc("raf r write", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "r")) { a.write(1); return "?"; } });
        tc("raf r setLength", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "r")) { a.setLength(1); return "?"; } });
        t("raf read after close", () -> { RandomAccessFile a = new RandomAccessFile(r, "r"); a.close(); a.close(); return a.read(); });
        t("raf length after close", () -> { RandomAccessFile a = new RandomAccessFile(r, "r"); a.close(); return a.length(); });
        t("raf read(b) at eof", () -> { try (RandomAccessFile a = new RandomAccessFile(r, "r")) { a.seek(a.length()); return a.read(new byte[3]) + " " + a.read(new byte[3], 0, 0); } });
        System.out.println("cleanup: " + delete(root));
        System.out.println("done");
    }

    static boolean delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        return f.delete();
    }
}
