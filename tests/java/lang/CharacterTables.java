// TIMEOUT: 300
package lang;

public class CharacterTables {
    static final String[] NAMES = {
        "getType", "toUpperCase", "toLowerCase", "toTitleCase", "digit36", "digit10", "digit16",
        "digit2", "getNumericValue", "isWhitespace", "isSpaceChar", "isMirrored", "isIdeographic",
        "isAlphabetic", "isLetter", "isDigit", "isLetterOrDigit", "isUpperCase", "isLowerCase",
        "isTitleCase", "isJavaIdentifierStart", "isJavaIdentifierPart", "isUnicodeIdentifierStart",
        "isUnicodeIdentifierPart", "isIdentifierIgnorable", "isISOControl", "isDefined",
        "getDirectionality", "charToUpperCase", "charGetType", "charIsLetter", "charIsJavaPart",
        "charDirectionality"};

    static long mix(long h, int v) {
        return (h ^ v) * 0x100000001b3L;
    }

    static int b(boolean v) {
        return v ? 1 : 0;
    }

    static void probe(int cp) {
        System.out.println("probe " + cp + ": type=" + Character.getType(cp) + " upper="
            + Character.toUpperCase(cp) + " lower=" + Character.toLowerCase(cp) + " digit="
            + Character.digit(cp, 10) + " num=" + Character.getNumericValue(cp) + " dir="
            + Character.getDirectionality(cp) + " letter=" + Character.isLetter(cp) + " ws="
            + Character.isWhitespace(cp) + " def=" + Character.isDefined(cp) + " iso="
            + Character.isISOControl(cp) + " jpart=" + Character.isJavaIdentifierPart(cp));
    }

    static void spot(int cp) {
        System.out.println("spot U+" + Integer.toHexString(cp) + ": type=" + Character.getType(cp)
            + " upper=" + Integer.toHexString(Character.toUpperCase(cp)) + " lower="
            + Integer.toHexString(Character.toLowerCase(cp)) + " title="
            + Integer.toHexString(Character.toTitleCase(cp)) + " digit36=" + Character.digit(cp, 36)
            + " num=" + Character.getNumericValue(cp) + " dir=" + Character.getDirectionality(cp)
            + " mirrored=" + Character.isMirrored(cp) + " ideo=" + Character.isIdeographic(cp)
            + " alpha=" + Character.isAlphabetic(cp));
    }

    public static void main(String[] args) {
        long[] h = new long[NAMES.length];
        java.util.Arrays.fill(h, 0xcbf29ce484222325L);
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            int i = 0;
            h[i] = mix(h[i], Character.getType(cp)); i++;
            h[i] = mix(h[i], Character.toUpperCase(cp)); i++;
            h[i] = mix(h[i], Character.toLowerCase(cp)); i++;
            h[i] = mix(h[i], Character.toTitleCase(cp)); i++;
            h[i] = mix(h[i], Character.digit(cp, 36)); i++;
            h[i] = mix(h[i], Character.digit(cp, 10)); i++;
            h[i] = mix(h[i], Character.digit(cp, 16)); i++;
            h[i] = mix(h[i], Character.digit(cp, 2)); i++;
            h[i] = mix(h[i], Character.getNumericValue(cp)); i++;
            h[i] = mix(h[i], b(Character.isWhitespace(cp))); i++;
            h[i] = mix(h[i], b(Character.isSpaceChar(cp))); i++;
            h[i] = mix(h[i], b(Character.isMirrored(cp))); i++;
            h[i] = mix(h[i], b(Character.isIdeographic(cp))); i++;
            h[i] = mix(h[i], b(Character.isAlphabetic(cp))); i++;
            h[i] = mix(h[i], b(Character.isLetter(cp))); i++;
            h[i] = mix(h[i], b(Character.isDigit(cp))); i++;
            h[i] = mix(h[i], b(Character.isLetterOrDigit(cp))); i++;
            h[i] = mix(h[i], b(Character.isUpperCase(cp))); i++;
            h[i] = mix(h[i], b(Character.isLowerCase(cp))); i++;
            h[i] = mix(h[i], b(Character.isTitleCase(cp))); i++;
            h[i] = mix(h[i], b(Character.isJavaIdentifierStart(cp))); i++;
            h[i] = mix(h[i], b(Character.isJavaIdentifierPart(cp))); i++;
            h[i] = mix(h[i], b(Character.isUnicodeIdentifierStart(cp))); i++;
            h[i] = mix(h[i], b(Character.isUnicodeIdentifierPart(cp))); i++;
            h[i] = mix(h[i], b(Character.isIdentifierIgnorable(cp))); i++;
            h[i] = mix(h[i], b(Character.isISOControl(cp))); i++;
            h[i] = mix(h[i], b(Character.isDefined(cp))); i++;
            h[i] = mix(h[i], Character.getDirectionality(cp)); i++;
            if (cp <= 0xFFFF) {
                char c = (char) cp;
                h[i] = mix(h[i], Character.toUpperCase(c)); i++;
                h[i] = mix(h[i], Character.getType(c)); i++;
                h[i] = mix(h[i], b(Character.isLetter(c))); i++;
                h[i] = mix(h[i], b(Character.isJavaIdentifierPart(c))); i++;
                h[i] = mix(h[i], Character.getDirectionality(c)); i++;
            }
        }
        for (int i = 0; i < NAMES.length; i++) {
            System.out.println(NAMES[i] + " " + Long.toHexString(h[i]));
        }

        for (int cp : new int[] {-1, Integer.MIN_VALUE, 0x110000, Integer.MAX_VALUE, 0x7FFFFFFF - 5}) {
            probe(cp);
        }
        int[] spots = {0, 9, 0x20, 0x30, 0x39, 0x41, 0x5A, 0x61, 0x7A, 0x7F, 0x85, 0xA0, 0xAA, 0xAD,
            0xB2, 0xB5, 0xBC, 0xC0, 0xDF, 0xFF, 0x130, 0x131, 0x149, 0x17F, 0x1C5, 0x1F2, 0x345,
            0x370, 0x390, 0x3A3, 0x3C2, 0x3F4, 0x41F, 0x5D0, 0x627, 0x660, 0x6F5, 0x966, 0x9F4,
            0xE50, 0x1680, 0x180E, 0x1F80, 0x1F88, 0x2007, 0x2028, 0x202F, 0x2160, 0x2170, 0x2189,
            0x24B6, 0x2460, 0x2FF0, 0x3007, 0x3021, 0x3400, 0x4E00, 0x9FCC, 0x9FCD, 0xAC00, 0xD800,
            0xDC00, 0xE000, 0xFB01, 0xFF21, 0xFF10, 0xFFFE, 0x10400, 0x10428, 0x1D7CE, 0x1F600,
            0x20000, 0x2FA1D, 0xE0001, 0xF0000, 0x10FFFF};
        for (int cp : spots) {
            spot(cp);
        }
        System.out.println("constants " + Character.UNASSIGNED + " " + Character.UPPERCASE_LETTER + " "
            + Character.LOWERCASE_LETTER + " " + Character.TITLECASE_LETTER + " " + Character.MODIFIER_LETTER
            + " " + Character.OTHER_LETTER + " " + Character.NON_SPACING_MARK + " " + Character.ENCLOSING_MARK
            + " " + Character.COMBINING_SPACING_MARK + " " + Character.DECIMAL_DIGIT_NUMBER + " "
            + Character.LETTER_NUMBER + " " + Character.OTHER_NUMBER + " " + Character.SPACE_SEPARATOR + " "
            + Character.LINE_SEPARATOR + " " + Character.PARAGRAPH_SEPARATOR + " " + Character.CONTROL + " "
            + Character.FORMAT + " " + Character.PRIVATE_USE + " " + Character.SURROGATE + " "
            + Character.DASH_PUNCTUATION + " " + Character.START_PUNCTUATION + " " + Character.END_PUNCTUATION
            + " " + Character.CONNECTOR_PUNCTUATION + " " + Character.OTHER_PUNCTUATION + " "
            + Character.MATH_SYMBOL + " " + Character.CURRENCY_SYMBOL + " " + Character.MODIFIER_SYMBOL + " "
            + Character.OTHER_SYMBOL + " " + Character.INITIAL_QUOTE_PUNCTUATION + " "
            + Character.FINAL_QUOTE_PUNCTUATION);
        System.out.println("directionality " + Character.DIRECTIONALITY_UNDEFINED + " "
            + Character.DIRECTIONALITY_LEFT_TO_RIGHT + " " + Character.DIRECTIONALITY_RIGHT_TO_LEFT + " "
            + Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC + " " + Character.DIRECTIONALITY_EUROPEAN_NUMBER
            + " " + Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR + " "
            + Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR + " " + Character.DIRECTIONALITY_ARABIC_NUMBER
            + " " + Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR + " "
            + Character.DIRECTIONALITY_NONSPACING_MARK + " " + Character.DIRECTIONALITY_BOUNDARY_NEUTRAL + " "
            + Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR + " " + Character.DIRECTIONALITY_SEGMENT_SEPARATOR
            + " " + Character.DIRECTIONALITY_WHITESPACE + " " + Character.DIRECTIONALITY_OTHER_NEUTRALS + " "
            + Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING + " "
            + Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE + " "
            + Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING + " "
            + Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE + " "
            + Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT);
        System.out.println("deprecated " + Character.isSpace(' ') + Character.isSpace(' ')
            + Character.isSpace('\n') + Character.isSpace('\u000b') + Character.isSpace('\f')
            + Character.isJavaLetter('$') + Character.isJavaLetter('1') + Character.isJavaLetterOrDigit('1'));
        System.out.println("radix " + Character.digit('z', 36) + " " + Character.digit('z', 35) + " "
            + Character.digit('Ａ', 16) + " " + Character.digit('٣', 10) + " "
            + Character.digit('7', 7) + " " + Character.digit('7', 8) + " " + Character.digit('a', 1)
            + " " + Character.digit('a', 37) + " " + Character.digit(0x1D7D8, 10));
    }
}
