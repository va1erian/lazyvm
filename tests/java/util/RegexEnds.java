package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hitEnd and requireEnd after matches(), lookingAt() and a failed find(). \b and $ at the end of
 * the text set requireEnd; where a failed search stops decides whether they are ever reached, and
 * that depends on how short the pattern's shortest match is taken to be.
 */
public class RegexEnds {
    static String flags(Matcher m) {
        return (m.hitEnd() ? "h" : "-") + (m.requireEnd() ? "r" : "-");
    }

    static void row(String re, String... texts) {
        StringBuilder sb = new StringBuilder(re + " :");
        for (String t : texts) {
            Matcher m = Pattern.compile(re).matcher(t);
            boolean found = m.find();
            sb.append(' ').append(found ? 'T' : 'F').append(flags(m));
            m = Pattern.compile(re).matcher(t);
            sb.append('/').append(m.matches() ? 'T' : 'F').append(flags(m));
            m = Pattern.compile(re).matcher(t);
            sb.append('/').append(m.lookingAt() ? 'T' : 'F').append(flags(m));
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        String[] texts = {"", "a", "ab", "b a", "aa\n"};
        System.out.println("== anchors at the end");
        for (String re : new String[] {"a$", "(?m)a$", "a\\z", "a\\Z", "(?m)a$|b", "a(?m:$)", "(?m)^a$", "a$|b", "\\ba", "a\\b", "a\\B", "(?m)$", "$", "a?$"}) {
            row(re, texts);
        }
        System.out.println("== a repeat of alternatives or a variable body counts nothing towards the shortest match");
        String[] loopTexts = {"", "B", "BC", "Bc", " B"};
        String[] repeats = {"\\b(?:B)+?z", "\\bB+?z", "\\b(?:B|C)+?z", "\\b(?:B|C)+z", "\\b(?:B|C)*z", "\\b(?:B|C)*?z", "\\b(?:B|C){2}z", "\\b(?:B|C){2,3}z",
            "\\b(B|C)+z", "\\b(?:BB?)+?z", "\\b(?:B\\p{Sm}??|C)+?z", "\\b(?:B+)+z", "\\b(?:B*C)+z", "\\b(?:B{1,2})+z", "\\b(?:B(?:C|D))+z", "\\b(?:B|C)+x*z",
            "x*\\b(?:B|C)+z", "\\b(?=B)(?:B|C)+z", "\\b(?:B|C)+", "\\b(?:B|C)+?", "\\bz|\\b(?:B|C)+?z"};
        for (String re : repeats) {
            row(re, loopTexts);
        }
        System.out.println("== bodies that count in full");
        for (String re : new String[] {"\\b(?:BC)+?z", "\\b(B)+?z", "\\b(?:B)*z", "\\b(?:BB)+z", "\\b(?:B\\d)+z", "\\b(?:[BC])+z", "\\b(?:B|C)?z", "\\b(?:BC){2}z",
            "\\b(BC)+z", "\\b(?:B\\b)+z", "\\b(?:B{2})+z", "\\b(?:B|C)++z", "\\b(?>B|C)+z", "\\b(?:B(?=C))+z", "\\b(?:B(?:C))+z", "\\b(?:B)+(?:C|D)z", "(?:B|C)+?z",
            "\\b(B)?\\1{3,}z", "\\b(B)\\1+z", "\\b(?:(B)\\1)+z"}) {
            row(re, loopTexts);
        }
        System.out.println("done");
    }
}
