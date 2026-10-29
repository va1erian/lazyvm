// VMARGS: -Duser.language=tr -Duser.country=TR
package util;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Property, block and script names under a Turkish default locale, where the default upper case
 * of i is a dotted capital I. The oracle folds the names the same way under every locale: the
 * whole name maps through Unicode case (dotless i, long s and the Kelvin sign fold to ASCII
 * letters), but never through the default locale. Each row prints which of the sample chars the
 * class matches, or the error.
 */
public class RegexNamesLocale {
    public static void main(String[] args) {
        // The VM arguments make Turkish the default on the oracle; a VM that does not read them
        // gets it here, so both run the names under the same default.
        Locale.setDefault(new Locale("tr", "TR"));
        String[] names = {
            "IsLatin", "InBasicLatin", "javaLowerCase", "IsAlphabetic", "IsIdeographic", "IsWhite_Space",
            "IsHex_Digit", "IsDigit", "IsLetter", "IsLowercase", "IsUppercase", "IsTitlecase", "IsControl",
            "IsPunctuation", "IsAssigned", "IsAlnum", "IsBlank", "IsInherited", "IsCommon", "InGreek",
            "IsGreek", "IsCyrillic", "InCJKUnifiedIdeographs", "InLatin_1_Supplement", "IsjavaLowerCase",
            "Lower", "IsLu", "Lu", "L", "LOWER", "ISLATIN", "INBASICLATIN",
            "InBasıcLatin", "IsLatın", "IsAlphabetıc", "InBaſicLatin", "İsLatin",
            "IsLatinK",
            "sc=Latin", "SC=Latin", "script=Latin", "SCRIPT=Latin", "Script=latin", "blk=BasicLatin",
            "BLOCK=BASICLATIN", "gc=Lu", "GENERAL_CATEGORY=Lu", "General_Category=Lu", "blK=BasicLatin",
            "ſc=Latin", "scrıpt=Latin", "SCRİPT=Latin",
        };
        String samples = "aAiıİ 1一ά";
        for (String n : names) {
            StringBuilder sb = new StringBuilder(escape(n)).append(": ");
            try {
                Pattern p = Pattern.compile("\\p{" + n + "}");
                for (int i = 0; i < samples.length(); i++) {
                    sb.append(p.matcher(samples.substring(i, i + 1)).matches() ? '1' : '0');
                }
            } catch (PatternSyntaxException e) {
                sb.append(escape(e.getDescription()));
            }
            System.out.println(sb);
        }
    }

    /** The text with every char above U+007E written as a backslash-u escape. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 126) {
                String hex = Integer.toHexString(c);
                sb.append("\\u");
                for (int k = hex.length(); k < 4; k++) {
                    sb.append('0');
                }
                sb.append(hex);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
