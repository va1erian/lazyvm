package java.nio.charset;

import java.util.HashMap;
import java.util.Map;

/** The six charsets every Java SE platform has, coded by {@link Codec}. */
final class StandardCharset extends Charset {
    private static final int ASCII = 0;
    private static final int LATIN1 = 1;
    private static final int UTF8 = 2;
    private static final int UTF16 = 3;
    private static final int UTF16BE = 4;
    private static final int UTF16LE = 5;

    // Spec N1: the alias sets, exactly.
    static final StandardCharset US_ASCII = new StandardCharset(ASCII, "US-ASCII", new String[] {
        "646", "ANSI_X3.4-1968", "ANSI_X3.4-1986", "ASCII", "IBM367", "ISO646-US", "ISO_646.irv:1991",
        "ascii7", "cp367", "csASCII", "default", "iso-ir-6", "iso_646.irv:1983", "us"});
    static final StandardCharset ISO_8859_1 = new StandardCharset(LATIN1, "ISO-8859-1", new String[] {
        "819", "8859_1", "IBM-819", "IBM819", "ISO8859-1", "ISO8859_1", "ISO_8859-1", "ISO_8859-1:1987",
        "ISO_8859_1", "cp819", "csISOLatin1", "iso-ir-100", "l1", "latin1"});
    static final StandardCharset UTF_8 = new StandardCharset(UTF8, "UTF-8", new String[] {
        "UTF8", "unicode-1-1-utf-8"});
    static final StandardCharset UTF_16 = new StandardCharset(UTF16, "UTF-16", new String[] {
        "UTF_16", "UnicodeBig", "unicode", "utf16"});
    static final StandardCharset UTF_16BE = new StandardCharset(UTF16BE, "UTF-16BE",
        new String[] {"ISO-10646-UCS-2", "UTF_16BE", "UnicodeBigUnmarked", "X-UTF-16BE"});
    static final StandardCharset UTF_16LE = new StandardCharset(UTF16LE, "UTF-16LE",
        new String[] {"UTF_16LE", "UnicodeLittleUnmarked", "X-UTF-16LE"});

    private static final StandardCharset[] ALL = {US_ASCII, ISO_8859_1, UTF_8, UTF_16, UTF_16BE, UTF_16LE};
    private static final Map<String, StandardCharset> BY_NAME = new HashMap<>();

    static {
        for (StandardCharset cs : ALL) {
            BY_NAME.put(key(cs.name()), cs);
            for (String a : cs.aliases()) {
                BY_NAME.put(key(a), cs);
            }
        }
    }

    private final int kind;

    private StandardCharset(int kind, String name, String[] aliases) {
        super(name, aliases);
        this.kind = kind;
    }

    /** Charset names ignore case (Spec N1); they are ASCII, so fold ASCII only. */
    private static String key(String name) {
        char[] c = name.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] >= 'A' && c[i] <= 'Z') {
                c[i] += 'a' - 'A';
            }
        }
        return new String(c);
    }

    static StandardCharset byName(String name) {
        return BY_NAME.get(key(name));
    }

    static StandardCharset[] all() {
        return ALL.clone();
    }

    private boolean unicode() {
        return kind >= UTF8;
    }

    // Spec N3: the Unicode charsets contain every charset; ISO-8859-1 contains US-ASCII.
    public boolean contains(Charset cs) {
        if (unicode() || cs == this) {
            return true;
        }
        return kind == LATIN1 && cs == US_ASCII;
    }

    Codec codec() {
        switch (kind) {
            case ASCII:
                return new Codec.Narrow(0x7F);
            case LATIN1:
                return new Codec.Narrow(0xFF);
            case UTF8:
                return new Utf8Codec();
            default:
                return new Utf16Codec(kind == UTF16, kind != UTF16LE);
        }
    }

    // Spec K3: chars per byte, average and max.
    public CharsetDecoder newDecoder() {
        return new CodecDecoder(this, kind >= UTF16 ? 0.5f : 1.0f, 1.0f, codec());
    }

    // Spec K3: bytes per char, average and max, and the default replacements.
    public CharsetEncoder newEncoder() {
        switch (kind) {
            case ASCII:
            case LATIN1:
                return new CodecEncoder(this, 1.0f, 1.0f, new byte[] {'?'}, codec());
            case UTF8:
                return new CodecEncoder(this, 1.1f, 3.0f, new byte[] {'?'}, codec());
            case UTF16:
                return new CodecEncoder(this, 2.0f, 4.0f, new byte[] {-1, -3}, codec());
            case UTF16BE:
                return new CodecEncoder(this, 2.0f, 2.0f, new byte[] {-1, -3}, codec());
            default:
                return new CodecEncoder(this, 2.0f, 2.0f, new byte[] {-3, -1}, codec());
        }
    }
}
