package nio;

import java.nio.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;

/** CharsetDecoder/Encoder step results with REPORT, IGNORE and REPLACE, the malformed lengths of
 *  bad UTF-8, CoderResult, and the coder state machine. */
public class CharsetCoders {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    static String hexs(String s) {
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

    /** Decodes with REPORT, printing each error and skipping it; then the leftover when not at end. */
    static String steps(Charset cs, byte[] b, boolean end) {
        CharsetDecoder d = cs.newDecoder();
        ByteBuffer in = ByteBuffer.wrap(b);
        CharBuffer out = CharBuffer.allocate(16);
        StringBuilder sb = new StringBuilder();
        while (true) {
            CoderResult r = d.decode(in, out, end);
            if (r.isError()) {
                sb.append(out.position()).append(':').append(r).append(' ');
                in.position(in.position() + r.length());
                continue;
            }
            sb.append(r);
            break;
        }
        if (end) sb.append(" flush=").append(d.flush(out));
        out.flip();
        sb.append(" chars=").append(out.length()).append(" left=").append(in.remaining());
        return sb.toString();
    }

    static final String[] UTF8 = {
        "C3", "C0 80", "C1 BF", "C2 C0", "C2 41", "E0 80 80", "E0 9F BF", "E0 A0", "E0 A0 41", "E0 41",
        "E2 82", "E2 82 41", "ED A0 80", "ED BF BF", "ED A0", "ED 80 41", "EF BF", "F0 80 80 80", "F0 8F BF BF",
        "F0 90", "F0 9F 98", "F0 9F 98 41", "F0 9F 41", "F4 90 80 80", "F4 8F BF", "F5 80", "F8 88 80 80 80",
        "FF", "80", "C3 A9 80", "E2", "F0", "E0 80", "F0 80", "F4 90", "ED A0 41", "EF BF BF", "F0 90 80",
    };

    public static void main(String[] args) throws Exception {
        Charset u8 = StandardCharsets.UTF_8;
        for (String c : UTF8) {
            System.out.println("utf8 [" + c + "] end: " + steps(u8, hex(c), true));
            System.out.println("utf8 [" + c + "] more: " + steps(u8, hex(c), false));
        }
        System.out.println("ascii [41 80 FF 42]: " + steps(StandardCharsets.US_ASCII, hex("41 80 FF 42"), true));
        System.out.println("latin1 [00 80 FF]: " + steps(StandardCharsets.ISO_8859_1, hex("00 80 FF"), true));
        for (String c : new String[]{"FE FF 00 41", "FF FE 41 00", "00 41", "00", "00 41 00", "D8 00 00 41", "DC 00", "D8 3D DE 00", "D8 3D", "FE FF FE FF 00 41", "00 41 FE FF"}) {
            for (Charset cs : new Charset[]{StandardCharsets.UTF_16, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE}) {
                System.out.println(cs + " [" + c + "]: " + steps(cs, hex(c), true));
            }
        }

        t("IGNORE", () -> u8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(hex("41 C3 42 E2 82 43"))));
        t("REPLACE x", () -> u8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).replaceWith("x").decode(ByteBuffer.wrap(hex("41 C3 42 F0 9F 98"))));
        t("REPORT decode()", () -> u8.newDecoder().decode(ByteBuffer.wrap(hex("41 C3 42"))));
        t("REPORT decode() truncated", () -> u8.newDecoder().decode(ByteBuffer.wrap(hex("41 E2 82"))));
        t("Charset.decode", () -> u8.decode(ByteBuffer.wrap(hex("41 C3 42"))));
        t("default actions", () -> { CharsetDecoder d = u8.newDecoder(); return d.malformedInputAction() + " " + d.unmappableCharacterAction() + " [" + d.replacement() + "]"; });
        t("decoder averages", () -> { CharsetDecoder d = u8.newDecoder(); return d.averageCharsPerByte() + " " + d.maxCharsPerByte(); });
        t("utf16 decoder averages", () -> { CharsetDecoder d = StandardCharsets.UTF_16.newDecoder(); return d.averageCharsPerByte() + " " + d.maxCharsPerByte(); });
        t("utf16be/le decoder averages", () -> StandardCharsets.UTF_16BE.newDecoder().averageCharsPerByte() + " " + StandardCharsets.UTF_16LE.newDecoder().averageCharsPerByte());
        for (String c : new String[]{"C2 41", "E2 82 41", "ED A0 80", "E0 A0 41", "F0 9F 98 41", "F0 9F 41 41", "80", "E2 41", "E0 80 80", "F0 80 80 80", "C3"}) {
            t("utf8 no room [" + c + "]", () -> { ByteBuffer in = ByteBuffer.wrap(hex(c)); CoderResult r = u8.newDecoder().decode(in, CharBuffer.allocate(0), true); return r + " " + in.position(); });
        }
        t("replaceWith empty", () -> u8.newDecoder().replaceWith("").replacement().length());
        t("replaceWith null", () -> u8.newDecoder().replaceWith(null));
        t("onMalformedInput null", () -> u8.newDecoder().onMalformedInput(null));
        t("decode after end without reset", () -> {
            CharsetDecoder d = u8.newDecoder();
            d.decode(ByteBuffer.wrap(hex("41")), CharBuffer.allocate(4), true);
            return d.decode(ByteBuffer.wrap(hex("41")), CharBuffer.allocate(4), false);
        });
        t("flush before end", () -> u8.newDecoder().flush(CharBuffer.allocate(4)));
        t("decode after flush, reset", () -> {
            CharsetDecoder d = u8.newDecoder();
            d.decode(ByteBuffer.wrap(hex("41")), CharBuffer.allocate(4), true);
            d.flush(CharBuffer.allocate(4));
            d.reset();
            return d.decode(ByteBuffer.wrap(hex("41")), CharBuffer.allocate(4), true);
        });
        t("decode overflow", () -> { ByteBuffer in = ByteBuffer.wrap(hex("41 42 43")); CoderResult r = u8.newDecoder().decode(in, CharBuffer.allocate(2), true); return r + " " + in.position(); });
        t("decode pair into 1 char", () -> { ByteBuffer in = ByteBuffer.wrap(hex("F0 9F 98 80")); CoderResult r = u8.newDecoder().decode(in, CharBuffer.allocate(1), true); return r + " " + in.position(); });

        for (Charset cs : new Charset[]{StandardCharsets.US_ASCII, StandardCharsets.ISO_8859_1, u8, StandardCharsets.UTF_16, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE}) {
            for (String s : new String[]{"Aé", "€", "😀", "\ud800A", "A\udc00", "\ud800"}) {
                t(cs + " encode " + hexs(s), () -> {
                    CharsetEncoder e = cs.newEncoder();
                    CharBuffer in = CharBuffer.wrap(s);
                    ByteBuffer out = ByteBuffer.allocate(16);
                    StringBuilder sb = new StringBuilder();
                    CoderResult r;
                    while ((r = e.encode(in, out, true)).isError()) { sb.append(r).append(' '); in.position(in.position() + r.length()); }
                    sb.append(r).append(" flush=").append(e.flush(out)).append(" bytes=").append(out.position());
                    return sb;
                });
            }
            CharsetEncoder e = cs.newEncoder();
            t(cs + " encoder", () -> e.averageBytesPerChar() + " " + e.maxBytesPerChar() + " repl=" + java.util.Arrays.toString(e.replacement()) + " canEncode(e9)=" + e.canEncode('é') + " canEncode(d800)=" + e.canEncode('\ud800') + " canEncode(pair)=" + e.canEncode("😀"));
        }
        t("encode lone high, not at end", () -> { CharsetEncoder e = u8.newEncoder(); CharBuffer in = CharBuffer.wrap("A\ud83d"); CoderResult r = e.encode(in, ByteBuffer.allocate(8), false); return r + " " + in.position(); });
        t("encoder REPORT encode()", () -> u8.newEncoder().encode(CharBuffer.wrap("a\ud800")));
        t("ascii REPORT encode()", () -> StandardCharsets.US_ASCII.newEncoder().encode(CharBuffer.wrap("aé")));
        t("replaceWith bad bytes", () -> StandardCharsets.US_ASCII.newEncoder().replaceWith(new byte[]{(byte) 0x80}));
        t("replaceWith too long", () -> StandardCharsets.US_ASCII.newEncoder().replaceWith(new byte[]{'*', '*'}));
        t("utf8 replaceWith 3 bytes", () -> java.util.Arrays.toString(u8.newEncoder().replaceWith(new byte[]{'<', '-', '>'}).replacement()));
        t("encode with REPLACE", () -> { ByteBuffer b = StandardCharsets.US_ASCII.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(new byte[]{'*'}).encode(CharBuffer.wrap("aé😀b")); return new String(b.array(), 0, b.limit(), "US-ASCII"); });

        t("CoderResult", () -> CoderResult.UNDERFLOW + " " + CoderResult.OVERFLOW + " " + CoderResult.malformedForLength(3) + " " + CoderResult.unmappableForLength(2));
        t("CoderResult flags", () -> { CoderResult m = CoderResult.malformedForLength(2); return m.isError() + " " + m.isMalformed() + " " + m.isUnmappable() + " " + m.isOverflow() + " " + m.isUnderflow() + " " + m.length() + " " + (m == CoderResult.malformedForLength(2)); });
        t("CoderResult length of UNDERFLOW", () -> CoderResult.UNDERFLOW.length());
        t("CoderResult malformed 0", () -> CoderResult.malformedForLength(0));
        t("throwException malformed", () -> { CoderResult.malformedForLength(2).throwException(); return "?"; });
        t("throwException unmappable", () -> { CoderResult.unmappableForLength(1).throwException(); return "?"; });
        t("throwException underflow", () -> { CoderResult.UNDERFLOW.throwException(); return "?"; });
        t("throwException overflow", () -> { CoderResult.OVERFLOW.throwException(); return "?"; });
        t("MalformedInputException", () -> new MalformedInputException(4).getMessage() + " " + new MalformedInputException(4).getInputLength());
        t("UnmappableCharacterException", () -> new UnmappableCharacterException(2).getMessage());
        t("CharacterCodingException", () -> new CharacterCodingException().getMessage());
        t("CodingErrorAction", () -> CodingErrorAction.IGNORE + " " + CodingErrorAction.REPLACE + " " + CodingErrorAction.REPORT);
        System.out.println("done");
    }
}
