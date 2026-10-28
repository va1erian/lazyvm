package io;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** String.getBytes and new String over the six standard charsets: unmappable and lone-surrogate
 *  replacement, UTF-16 byte order marks, and OutputStreamWriter with surrogates split across writes. */
public class CharsetsEncode {
    static final String[] NAMES = {"US-ASCII", "ISO-8859-1", "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE"};

    static String bytes(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(sb.length() == 0 ? "" : " ").append(String.format("%02X", x & 0xff));
        return sb.toString();
    }

    static String chars(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(sb.length() == 0 ? "" : " ").append(String.format("%04X", (int) c));
        return sb.toString();
    }

    static byte[] hex(String s) {
        String[] p = s.trim().split(" +");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    static String viaWriter(String cs, String[] parts) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        OutputStreamWriter w = new OutputStreamWriter(bo, cs);
        StringBuilder sizes = new StringBuilder();
        for (String p : parts) { w.write(p); w.flush(); sizes.append(bo.size()).append(','); }
        w.close();
        return bytes(bo.toByteArray()) + " (after each flush " + sizes + ")";
    }

    public static void main(String[] args) throws Exception {
        String[] samples = {"", "A", "é", "ÿĀ", "€", "😀", "\ud800", "\udc00", "A\ud800B",
            "\udc00\ud800", "\ud83d😀", "﻿", "￾", "￿"};
        for (String n : NAMES) {
            for (String s : samples) {
                System.out.println(n + " getBytes [" + chars(s) + "] = " + bytes(s.getBytes(n)) + (java.util.Arrays.equals(s.getBytes(n), s.getBytes(Charset.forName(n))) ? "" : " | Charset differs"));
            }
        }
        String[] dec = {"FE FF 00 41", "FF FE 41 00", "00 41", "41 00", "FE FF", "FF FE", "FE", "00 41 00", "FE FF D8 3D", "D8 3D DE 00",
            "DE 00 D8 3D", "D8 3D 00 41", "D8 3D D8 3D DE 00", "FF FE FF FE", "FE FF FF FE", "00 41 FF FE 00 42", "EF BB BF 41", "80 FF"};
        for (String n : NAMES) {
            for (String d : dec) {
                System.out.println(n + " new String [" + d + "] = " + chars(new String(hex(d), n)));
            }
        }
        for (String n : new String[]{"UTF-8", "UTF-16", "UTF-16LE", "US-ASCII"}) {
            System.out.println(n + " writer pair split: " + viaWriter(n, new String[]{"A\ud83d", "\ude00B"}));
            System.out.println(n + " writer lone high then end: " + viaWriter(n, new String[]{"A\ud83d"}));
            System.out.println(n + " writer high then high: " + viaWriter(n, new String[]{"\ud83d", "\ud83d", "\ude00"}));
            System.out.println(n + " writer two parts: " + viaWriter(n, new String[]{"A", "B"}));
        }
        // InputStreamReader over UTF-16 reads the BOM once, at the start.
        for (String d : new String[]{"FE FF 00 41 FE FF 00 42", "FF FE 41 00 42 00", "00 41 00", "D8 3D", "FF FE 41 00 3D D8", "FE FF 00 41 D8 3D"}) {
            InputStreamReader r = new InputStreamReader(new ByteArrayInputStream(hex(d)), "UTF-16");
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) >= 0) sb.append((char) c);
            System.out.println("reader UTF-16 [" + d + "] = " + chars(sb.toString()));
        }
        // A reader or writer built from a coder keeps that coder's REPORT action.
        try {
            Reader r = new InputStreamReader(new ByteArrayInputStream(hex("41 C3 42")), StandardCharsets.UTF_8.newDecoder());
            System.out.print("reader with REPORT decoder: ");
            System.out.print((char) r.read() + " then ");
            r.read();
            System.out.println("no error");
        } catch (IOException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
        try {
            Writer w = new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8.newEncoder());
            w.write("a\udc00b");
            w.flush();
            System.out.println("writer with REPORT encoder: no error");
        } catch (IOException e) {
            System.out.println("writer with REPORT encoder: " + e.getClass().getName() + ": " + e.getMessage());
        }
        try {
            "x".getBytes("no-such-charset");
        } catch (UnsupportedEncodingException e) {
            System.out.println("getBytes bad name: " + e.getMessage());
        }
        try {
            new String(new byte[1], "no-such-charset");
        } catch (UnsupportedEncodingException e) {
            System.out.println("new String bad name: " + e.getMessage());
        }
        try {
            new InputStreamReader(new ByteArrayInputStream(new byte[0]), "no-such-charset");
        } catch (UnsupportedEncodingException e) {
            System.out.println("reader bad name: " + e.getMessage());
        }
        try {
            new OutputStreamWriter(new ByteArrayOutputStream(), "bad name!");
        } catch (Exception e) {
            System.out.println("writer illegal name: " + e.getClass().getName() + ": " + e.getMessage());
        }
        try {
            "x".getBytes((String) null);
        } catch (Exception e) {
            System.out.println("getBytes null: " + e.getClass().getName());
        }
        System.out.println("getEncoding: " + new InputStreamReader(new ByteArrayInputStream(new byte[0]), "utf8").getEncoding()
            + " " + new OutputStreamWriter(new ByteArrayOutputStream(), "latin1").getEncoding()
            + " " + new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-16").getEncoding()
            + " " + new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-16LE").getEncoding()
            + " " + new OutputStreamWriter(new ByteArrayOutputStream(), "ascii").getEncoding()
            + " " + new OutputStreamWriter(new ByteArrayOutputStream(), Charset.forName("UTF-16BE")).getEncoding());
        OutputStreamWriter closed = new OutputStreamWriter(new ByteArrayOutputStream(), "UTF-8");
        closed.close();
        closed.close();
        System.out.println("closed getEncoding: " + closed.getEncoding());
        try {
            closed.write('x');
        } catch (IOException e) {
            System.out.println("closed write: " + e.getMessage());
        }
        System.out.println("done");
    }
}
