package lang;

import java.util.Arrays;

/** String.split on one plain or escaped character (LazyVM's path without a Pattern), and on regexes next to them. */
public class StringSplit {
    static void show(String s, String regex, int limit) {
        String r;
        try {
            String[] parts = s.split(regex, limit);
            r = Arrays.toString(parts) + " " + parts.length;
        } catch (RuntimeException e) {
            r = e.getClass().getName();
        }
        System.out.println("[" + s + "] / [" + regex + "] / " + limit + " -> " + r);
    }

    public static void main(String[] args) {
        String[] inputs = {"", ",", ",,", "a", "a,b", "a,b,", "a,b,,", ",a", ",,a,,b,,", "a,,b", "x:y:z", "a.b.c",
            "a|b", "a\\b", "a b  c", "tab\there"};
        String[] regexes = {",", ":", "\\.", "\\|", "\\\\", " ", "\t", ".", "|", "\\,", "\\:", ",+", "[,:]", "\\t",
            "\\d", "\\-", "-", "#"};
        int[] limits = {0, -1, 1, 2, 3};
        for (String s : inputs) {
            for (String regex : regexes) {
                for (int limit : limits) {
                    show(s, regex, limit);
                }
            }
        }
        System.out.println(Arrays.toString("a1b2c".split("\\d")));
        System.out.println(Arrays.toString("Grass_Tile".split("_")));
        System.out.println(Arrays.toString("k1:v1,k2:v2".split(",")[1].split(":")));
        show("a\ud800b", "\ud800", 0);
        show("a-b-c", "-", 0);
        show("a]b", "]", 0);
        show("a}b", "}", 0);
        show("a{b", "{", 0);
    }
}
