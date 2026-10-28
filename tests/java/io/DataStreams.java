package io;

import java.io.*;
import java.util.concurrent.Callable;

/** DataOutputStream byte layout, modified UTF-8 and its limits, DataInputStream end-of-file and
 *  malformed-UTF errors. */
public class DataStreams {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    interface W { void write(DataOutputStream d) throws IOException; }

    static String bytes(W w) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        w.write(d);
        StringBuilder sb = new StringBuilder();
        for (byte b : bo.toByteArray()) sb.append(String.format("%02X", b & 0xff));
        return sb + " size=" + d.size();
    }

    static DataInputStream in(int... b) {
        byte[] a = new byte[b.length];
        for (int i = 0; i < b.length; i++) a[i] = (byte) b[i];
        return new DataInputStream(new ByteArrayInputStream(a));
    }

    static String rep(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    public static void main(String[] args) throws Exception {
        t("int", () -> bytes(d -> d.writeInt(0x01020304)));
        t("negative int", () -> bytes(d -> d.writeInt(-2)));
        t("long", () -> bytes(d -> d.writeLong(0x0102030405060708L)));
        t("short", () -> bytes(d -> d.writeShort(0x12345)));
        t("char", () -> bytes(d -> d.writeChar(0x1ffff)));
        t("byte", () -> bytes(d -> d.writeByte(-129)));
        t("boolean", () -> bytes(d -> { d.writeBoolean(true); d.writeBoolean(false); }));
        t("float", () -> bytes(d -> { d.writeFloat(1.0f); d.writeFloat(Float.NaN); d.writeFloat(Float.intBitsToFloat(0x7fc00001)); }));
        t("double", () -> bytes(d -> { d.writeDouble(-0.0); d.writeDouble(Double.NaN); }));
        t("writeBytes", () -> bytes(d -> d.writeBytes("Aé€")));
        t("writeChars", () -> bytes(d -> d.writeChars("A€")));
        t("writeUTF", () -> bytes(d -> d.writeUTF("A\u0000é߿ࠀ€😀\ud800")));
        t("writeUTF empty", () -> bytes(d -> d.writeUTF("")));
        t("writeUTF 65535", () -> bytes(d -> d.writeUTF(rep('a', 65535))).substring(0, 8));
        t("writeUTF 65536", () -> bytes(d -> d.writeUTF(rep('a', 65536))));
        t("writeUTF 21845 euros", () -> bytes(d -> d.writeUTF(rep('€', 21845))).substring(0, 8));
        t("writeUTF 21846 euros", () -> bytes(d -> d.writeUTF(rep('€', 21846))));
        t("writeUTF 40000 nuls", () -> bytes(d -> d.writeUTF(rep('\0', 40000))));
        t("writeUTF null", () -> bytes(d -> d.writeUTF(null)));
        t("size after failed writeUTF", () -> { DataOutputStream d = new DataOutputStream(new ByteArrayOutputStream()); d.write(1); try { d.writeUTF(rep('a', 70000)); } catch (UTFDataFormatException e) { } return d.size(); });
        t("write(b,off,len) size", () -> bytes(d -> d.write(new byte[]{1, 2, 3, 4}, 1, 2)));
        t("static readUTF", () -> DataInputStream.readUTF(in(0, 2, 0x41, 0x42)));

        t("readUTF", () -> in(0, 8, 0x41, 0xC0, 0x80, 0xC3, 0xA9, 0xE2, 0x82, 0xAC).readUTF().length());
        t("readUTF nul forms", () -> { String s = in(0, 3, 0, 0xC0, 0x80).readUTF(); return s.length() + " " + (int) s.charAt(0) + " " + (int) s.charAt(1); });
        t("readUTF overlong ok", () -> (int) in(0, 2, 0xC1, 0x81).readUTF().charAt(0));
        t("readUTF 6-byte pair", () -> { String s = in(0, 6, 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80).readUTF(); return Integer.toHexString(s.charAt(0)) + Integer.toHexString(s.charAt(1)); });
        t("readUTF 4-byte form", () -> in(0, 4, 0xF0, 0x9F, 0x98, 0x80).readUTF());
        t("readUTF lone continuation", () -> in(0, 3, 0x41, 0x80, 0x42).readUTF());
        t("readUTF bad second byte", () -> in(0, 3, 0x41, 0xC3, 0x41).readUTF());
        t("readUTF bad third byte", () -> in(0, 4, 0x41, 0xE2, 0x82, 0x41).readUTF());
        t("readUTF partial at end 2", () -> in(0, 2, 0x41, 0xC3).readUTF());
        t("readUTF partial at end 3", () -> in(0, 3, 0x41, 0xE2, 0x82).readUTF());
        t("readUTF F8", () -> in(0, 2, 0x41, 0xF8).readUTF());
        t("readUTF C3 41 at 0", () -> in(0, 2, 0xC3, 0x41).readUTF());
        t("readUTF E2 41 41 at 0", () -> in(0, 3, 0xE2, 0x41, 0x41).readUTF());
        t("readUTF E2 41 41 at 1", () -> in(0, 4, 0x41, 0xE2, 0x41, 0x41).readUTF());
        t("readUTF E2 41 at end", () -> in(0, 3, 0x41, 0xE2, 0x41).readUTF());
        t("readUTF C3 C3 A9", () -> in(0, 3, 0xC3, 0xC3, 0xA9).readUTF());
        t("readUTF short data", () -> in(0, 5, 0x41).readUTF());
        t("readUTF no length", () -> in(0).readUTF());
        t("readUTF empty", () -> in().readUTF());

        t("readInt", () -> Integer.toHexString(in(0x80, 0, 0, 1).readInt()));
        t("readInt short", () -> in(1, 2, 3).readInt());
        t("readLong short", () -> in(1, 2, 3, 4, 5, 6, 7).readLong());
        t("readShort", () -> in(0xff, 0xfe).readShort() + " " + in(0xff, 0xfe).readUnsignedShort() + " " + (int) in(0xff, 0xfe).readChar());
        t("readByte", () -> in(0xff).readByte() + " " + in(0xff).readUnsignedByte() + " " + in(2).readBoolean() + " " + in(0).readBoolean());
        t("readByte eof", () -> in().readByte());
        t("readBoolean eof", () -> in().readBoolean());
        t("readChar eof", () -> in(1).readChar());
        t("readFloat/Double", () -> in(0x3f, 0xc0, 0, 0).readFloat() + " " + in(0xbf, 0xf0, 0, 0, 0, 0, 0, 0).readDouble());
        t("readFully", () -> { byte[] b = new byte[3]; in(1, 2, 3, 4).readFully(b); return b[2]; });
        t("readFully short", () -> { in(1, 2).readFully(new byte[3]); return "?"; });
        t("readFully len 0 at eof", () -> { in().readFully(new byte[3], 0, 0); return "ok"; });
        t("readFully bounds", () -> { in(1, 2).readFully(new byte[3], 2, 2); return "?"; });
        t("skipBytes", () -> { DataInputStream d = in(1, 2, 3); return d.skipBytes(2) + " " + d.skipBytes(5) + " " + d.skipBytes(-1); });
        t("read(b) at eof", () -> in().read(new byte[2]));
        t("readLine", () -> {
            DataInputStream d = in('a', '\r', '\n', 'b', '\r', 'c', '\n', 0xe9, '\r');
            @SuppressWarnings("deprecation") String s = d.readLine() + "|" + d.readLine() + "|" + d.readLine() + "|" + d.readLine() + "|" + d.readLine();
            return s.replace('é', 'E');
        });
        t("DataInput over chunked stream", () -> {
            InputStream oneByOne = new InputStream() {
                int i;
                public int read() { return i < 4 ? i++ + 1 : -1; }
                public int read(byte[] b, int off, int len) { int c = read(); if (c < 0) return -1; b[off] = (byte) c; return 1; }
            };
            return Integer.toHexString(new DataInputStream(oneByOne).readInt());
        });
        t("UTFDataFormatException", () -> new UTFDataFormatException("x").getMessage() + " " + (new UTFDataFormatException() instanceof IOException));
        t("EOFException", () -> new EOFException().getMessage() + " " + new EOFException("m").getMessage());
        System.out.println("done");
    }
}
