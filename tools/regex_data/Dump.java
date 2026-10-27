import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prints the code point ranges and accepted names of every Unicode block and
 * script, as the Java 8 oracle reports them through Character.UnicodeBlock and
 * Character.UnicodeScript. tools/regex_data.py turns the output into
 * java/lazyrt/src/java/util/regex/UnicodeData.java.
 *
 * Line format: "B|S TAB NAME|ALT|ALT TAB lo-hi,lo-hi".
 */
public class Dump {
    // Names the oracle accepts for a block that are not spellings of its constant name.
    private static final String[][] BLOCK_EXTRAS = {
        {"GREEK", "Greek and Coptic"},
        {"CYRILLIC_SUPPLEMENTARY", "Cyrillic Supplement"},
        {"COMBINING_MARKS_FOR_SYMBOLS", "Combining Diacritical Marks for Symbols"},
        {"PRIVATE_USE_AREA", "Private Use"},
    };

    private static void spellings(String[] words, int k, String cur, List<String> out) {
        if (k == words.length) {
            out.add(cur);
            return;
        }
        for (String sep : new String[] {" ", "-", ""}) {
            spellings(words, k + 1, cur + sep + words[k], out);
        }
    }

    private static boolean blockName(String s) {
        try {
            Character.UnicodeBlock.forName(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void add(Map<String, StringBuilder> map, Object key, int lo, int hi) {
        if (key == null) {
            return;
        }
        StringBuilder sb = map.get(key.toString());
        if (sb == null) {
            sb = new StringBuilder();
            map.put(key.toString(), sb);
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(Integer.toHexString(lo)).append('-').append(Integer.toHexString(hi));
    }

    private static String ranges(Map<String, StringBuilder> map, String key) {
        StringBuilder sb = map.get(key);
        return sb == null ? "" : sb.toString();
    }

    public static void main(String[] args) {
        Map<String, StringBuilder> scripts = new LinkedHashMap<String, StringBuilder>();
        Map<String, StringBuilder> blocks = new LinkedHashMap<String, StringBuilder>();
        Object ps = null;
        Object pb = null;
        int ss = 0;
        int bs = 0;
        for (int c = 0; c <= 0x110000; c++) {
            Object s = c <= 0x10FFFF ? Character.UnicodeScript.of(c) : null;
            Object b = c <= 0x10FFFF ? Character.UnicodeBlock.of(c) : null;
            if (s != ps) {
                add(scripts, ps, ss, c - 1);
                ps = s;
                ss = c;
            }
            if (b != pb) {
                add(blocks, pb, bs, c - 1);
                pb = b;
                bs = c;
            }
        }
        Set<String> names = new LinkedHashSet<String>(blocks.keySet());
        names.add("SURROGATES_AREA");
        for (String name : names) {
            List<String> alts = new ArrayList<String>();
            String[] words = name.split("_");
            spellings(words, 1, words[0], alts);
            StringBuilder line = new StringBuilder("B	" + name);
            for (String alt : alts) {
                if (!alt.equals(name) && blockName(alt)) {
                    line.append('|').append(alt.toUpperCase());
                }
            }
            for (String[] extra : BLOCK_EXTRAS) {
                for (String form : new String[] {extra[1], extra[1].replace(" ", "")}) {
                    if (extra[0].equals(name) && blockName(form)) {
                        line.append('|').append(form.toUpperCase());
                    }
                }
            }
            System.out.println(line + "	" + ranges(blocks, name));
        }
        Map<String, List<String>> codes = new LinkedHashMap<String, List<String>>();
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    for (char d = 'a'; d <= 'z'; d++) {
                        String code = "" + a + b + c + d;
                        try {
                            String full = Character.UnicodeScript.forName(code).name();
                            if (!codes.containsKey(full)) {
                                codes.put(full, new ArrayList<String>());
                            }
                            codes.get(full).add(code.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            // not a script code
                        }
                    }
                }
            }
        }
        for (Character.UnicodeScript s : Character.UnicodeScript.values()) {
            StringBuilder line = new StringBuilder("S	" + s.name());
            List<String> alts = codes.get(s.name());
            if (alts != null) {
                for (String code : alts) {
                    line.append('|').append(code);
                }
            }
            System.out.println(line + "	" + ranges(scripts, s.name()));
        }
    }
}
