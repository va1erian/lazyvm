package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** A named charset; LazyVM provides the six standard ones (StandardCharset). */
public abstract class Charset implements Comparable<Charset> {
    private final String name;
    private final Set<String> aliases;

    protected Charset(String canonicalName, String[] aliases) {
        checkName(canonicalName);
        Set<String> set = new HashSet<>();
        if (aliases != null) {
            for (String a : aliases) {
                checkName(a);
                set.add(a);
            }
        }
        this.name = canonicalName;
        this.aliases = Collections.unmodifiableSet(set);
    }

    // Spec N2: a letter or digit, then letters, digits and - + : _ . (ASCII only).
    private static void checkName(String s) {
        if (s.isEmpty()) {
            throw new IllegalCharsetNameException(s);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean alnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alnum && (i == 0 || "-+:_.".indexOf(c) < 0)) {
                throw new IllegalCharsetNameException(s);
            }
        }
    }

    /** The charset for a legal name, or null; Spec N2's checks first. */
    private static Charset lookup(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Null charset name");
        }
        checkName(name);
        return StandardCharset.byName(name);
    }

    public static boolean isSupported(String charsetName) {
        return lookup(charsetName) != null;
    }

    public static Charset forName(String charsetName) {
        Charset cs = lookup(charsetName);
        if (cs == null) {
            throw new UnsupportedCharsetException(charsetName);
        }
        return cs;
    }

    // Spec N3: keys ignore case.
    public static SortedMap<String, Charset> availableCharsets() {
        TreeMap<String, Charset> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Charset cs : StandardCharset.all()) {
            m.put(cs.name(), cs);
        }
        return Collections.unmodifiableSortedMap(m);
    }

    // Spec N3: follows file.encoding, UTF-8 when that is not supported.
    public static Charset defaultCharset() {
        String enc = System.getProperty("file.encoding");
        try {
            if (enc != null && isSupported(enc)) {
                return forName(enc);
            }
        } catch (IllegalArgumentException e) {
            // An illegal name in the property falls back like an unknown one.
        }
        return StandardCharsets.UTF_8;
    }

    public final String name() {
        return name;
    }

    public final Set<String> aliases() {
        return aliases;
    }

    public String displayName() {
        return name;
    }

    public String displayName(Locale locale) {
        return name;
    }

    // Javadoc: names starting with "X-" or "x-" are not registered with IANA.
    public final boolean isRegistered() {
        return !name.startsWith("X-") && !name.startsWith("x-");
    }

    public abstract boolean contains(Charset cs);

    public abstract CharsetDecoder newDecoder();

    public abstract CharsetEncoder newEncoder();

    public boolean canEncode() {
        return true;
    }

    // Spec K4: Charset.decode and encode replace.
    public final CharBuffer decode(ByteBuffer bb) {
        try {
            return newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(bb);
        } catch (CharacterCodingException x) {
            throw new IllegalStateException(x);
        }
    }

    public final ByteBuffer encode(CharBuffer cb) {
        try {
            return newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).encode(cb);
        } catch (CharacterCodingException x) {
            throw new IllegalStateException(x);
        }
    }

    public final ByteBuffer encode(String str) {
        return encode(CharBuffer.wrap(str));
    }

    // Spec N3: names compared ignoring case.
    public final int compareTo(Charset that) {
        return name().compareToIgnoreCase(that.name());
    }

    public final int hashCode() {
        return name().hashCode();
    }

    public final boolean equals(Object ob) {
        return ob == this || (ob instanceof Charset && name.equals(((Charset) ob).name()));
    }

    public final String toString() {
        return name;
    }
}
