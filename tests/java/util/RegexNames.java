package util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which code points \p{name} accepts, for names that route differently depending on a prefix or on
 * UNICODE_CHARACTER_CLASS. Each row is one bit per sample code point (1 = matches), for the
 * default flags and then for UNICODE_CHARACTER_CLASS; "-" marks a name the oracle rejects.
 */
public class RegexNames {
    // 0 9 A F a f z _ ( ' ' tab 7F A0 AA B5 C0 E9 100 1C5 2B0 300 3A9 660 6F0 966 1680 2003 200C 200D 2028 2160 3000 3007
    // FDD0 FF10 FF21 FF41 10107 1D400 1D7CE 1F600 E0001 E0080 F0000 10FFFE D800 DC00
    static final int[] SAMPLES = {'0', '9', 'A', 'F', 'a', 'f', 'z', '_', '(', ' ', '\t', 0x7F, 0xA0, 0xAA, 0xB5, 0xC0, 0xE9, 0x100,
        0x1C5, 0x2B0, 0x300, 0x3A9, 0x660, 0x6F0, 0x966, 0x1680, 0x2003, 0x200C, 0x200D, 0x2028, 0x2160, 0x3000, 0x3007, 0xFDD0,
        0xFF10, 0xFF21, 0xFF41, 0x10107, 0x1D400, 0x1D7CE, 0x1F600, 0xE0001, 0xE0080, 0xF0000, 0x10FFFE, 0xD800, 0xDC00};

    static String bits(String name, int flags) {
        StringBuilder sb = new StringBuilder();
        try {
            Pattern p = Pattern.compile("\\p{" + name + "}", flags);
            for (int cp : SAMPLES) {
                String s = cp >= 0x10000 ? new String(Character.toChars(cp)) : String.valueOf((char) cp);
                sb.append(p.matcher(s).matches() ? '1' : '0');
            }
        } catch (PatternSyntaxException e) {
            sb.append('-');
        }
        return sb.toString();
    }

    static void rows(String title, String... names) {
        System.out.println("== " + title);
        for (String n : names) {
            System.out.println(n + " " + bits(n, 0) + " " + bits(n, Pattern.UNICODE_CHARACTER_CLASS));
        }
    }

    public static void main(String[] args) {
        rows("POSIX names, plain", "Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space");
        rows("POSIX names with Is: the flag changes nothing; five are Unicode sets, the rest ASCII",
            "IsLower", "IsUpper", "IsASCII", "IsAlpha", "IsDigit", "IsAlnum", "IsPunct", "IsGraph", "IsPrint", "IsBlank", "IsCntrl", "IsXDigit", "IsSpace");
        rows("binary properties (any case after Is)", "IsAlphabetic", "IsALPHABETIC", "IsIdeographic", "IsLetter", "IsLowercase", "IsUppercase",
            "IsTitlecase", "IsPunctuation", "IsControl", "IsWhite_Space", "IsWhiteSpace", "IsHex_Digit", "IsHEXDIGIT", "IsJoin_Control",
            "IsNoncharacter_Code_Point", "IsAssigned", "IsWord", "IsWORD", "Word", "Alphabetic");
        rows("LD, all and the category C", "LD", "IsLD", "gc=LD", "Isld", "all", "Isall", "gc=all", "IsALL", "ALL", "C", "IsC", "gc=C", "Cn", "Co", "Cs", "Cc", "Cf", "L", "LC", "Lu");
        rows("java names, with and without Is", "javaLowerCase", "IsjavaLowerCase", "javaLetterOrDigit", "IsjavaLetterOrDigit", "javaDefined",
            "IsjavaDefined", "javaWhitespace", "IsjavaWhitespace", "javaMirrored", "IsjavaMirrored", "javaUnicodeIdentifierPart", "IsjavaUnicodeIdentifierPart", "IsjavaSomething");
        rows("scripts and blocks", "IsLatin", "IsLatn", "IsGreek", "IsCommon", "InGreek", "InBasicLatin", "Latin", "IsInGreek", "block=Greek", "sc=Latin", "script=Common");
        rows("gc= takes POSIX, java and category names, case-sensitively; a bare POSIX name is case-insensitive only under UNICODE_CHARACTER_CLASS",
            "gc=Lower", "general_category=Alpha", "GC=Digit", "gc=javaLowerCase", "gc=javalowercase", "gc=alpha", "gc=IsLower", "gc=Lu", "gc=lu", "gc=Word",
            "lower", "LOWER", "alpha", "ALPHA", "aLpHa", "xdigit", "ascii", "isalpha", "Alpha", "Lower");
        System.out.println("done");
    }
}
