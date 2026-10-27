package lazy.internal;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

/** Charset names as java.io and String take them: one lookup and one naming rule for all. */
public final class Encodings {
    private Encodings() {
    }

    // Spec E2: an unknown or illegal name is UnsupportedEncodingException(name); null is NPE.
    public static Charset forName(String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        try {
            return Charset.forName(charsetName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException(charsetName);
        }
    }

    /** Spec R4: the historical names InputStreamReader and OutputStreamWriter report. */
    public static String historicalName(Charset cs) {
        switch (cs.name()) {
            case "UTF-8":
                return "UTF8";
            case "ISO-8859-1":
                return "ISO8859_1";
            case "US-ASCII":
                return "ASCII";
            case "UTF-16BE":
                return "UnicodeBigUnmarked";
            case "UTF-16LE":
                return "UnicodeLittleUnmarked";
            default:
                return cs.name();
        }
    }
}
