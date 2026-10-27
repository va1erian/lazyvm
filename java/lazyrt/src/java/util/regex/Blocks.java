package java.util.regex;

/**
 * Small tables behind \p{InBlock} and \p{IsScript}. lazyrt has no
 * Character.UnicodeBlock or UnicodeScript, so only the common blocks and
 * scripts listed here exist (docs/regex.md); any other name is rejected as
 * unknown rather than guessed.
 */
final class Blocks {
    // Each entry: normalized name(s) separated by '|', then lo, hi.
    private static final Object[][] BLOCKS = {
        {"BASICLATIN", 0x0, 0x7F}, {"LATIN1SUPPLEMENT", 0x80, 0xFF},
        {"LATINEXTENDEDA", 0x100, 0x17F}, {"LATINEXTENDEDB", 0x180, 0x24F},
        {"IPAEXTENSIONS", 0x250, 0x2AF}, {"SPACINGMODIFIERLETTERS", 0x2B0, 0x2FF},
        {"COMBININGDIACRITICALMARKS", 0x300, 0x36F}, {"GREEKANDCOPTIC|GREEK", 0x370, 0x3FF},
        {"CYRILLIC|CYRL", 0x400, 0x4FF}, {"CYRILLICSUPPLEMENT|CYRILLICSUPPLEMENTARY", 0x500, 0x52F},
        {"ARMENIAN|ARMN", 0x530, 0x58F}, {"HEBREW|HEBR", 0x590, 0x5FF}, {"ARABIC|ARAB", 0x600, 0x6FF},
        {"DEVANAGARI", 0x900, 0x97F}, {"THAI", 0xE00, 0xE7F}, {"GEORGIAN", 0x10A0, 0x10FF},
        {"HANGULJAMO", 0x1100, 0x11FF}, {"LATINEXTENDEDADDITIONAL", 0x1E00, 0x1EFF},
        {"GREEKEXTENDED", 0x1F00, 0x1FFF}, {"GENERALPUNCTUATION", 0x2000, 0x206F},
        {"SUPERSCRIPTSANDSUBSCRIPTS", 0x2070, 0x209F}, {"CURRENCYSYMBOLS", 0x20A0, 0x20CF},
        {"LETTERLIKESYMBOLS", 0x2100, 0x214F}, {"NUMBERFORMS", 0x2150, 0x218F},
        {"ARROWS", 0x2190, 0x21FF}, {"MATHEMATICALOPERATORS", 0x2200, 0x22FF},
        {"MISCELLANEOUSTECHNICAL", 0x2300, 0x23FF}, {"BOXDRAWING", 0x2500, 0x257F},
        {"BLOCKELEMENTS", 0x2580, 0x259F}, {"GEOMETRICSHAPES", 0x25A0, 0x25FF},
        {"MISCELLANEOUSSYMBOLS", 0x2600, 0x26FF}, {"DINGBATS", 0x2700, 0x27BF},
        {"CJKSYMBOLSANDPUNCTUATION", 0x3000, 0x303F}, {"HIRAGANA|HIRA", 0x3040, 0x309F},
        {"KATAKANA|KANA", 0x30A0, 0x30FF}, {"CJKUNIFIEDIDEOGRAPHS", 0x4E00, 0x9FFF},
        {"HANGULSYLLABLES", 0xAC00, 0xD7AF}, {"PRIVATEUSEAREA|PRIVATEUSE", 0xE000, 0xF8FF},
        {"HALFWIDTHANDFULLWIDTHFORMS", 0xFF00, 0xFFEF}, {"SPECIALS", 0xFFF0, 0xFFFF},
        {"EMOTICONS", 0x1F600, 0x1F64F},
    };

    // Scripts: normalized names (full name and ISO 15924 code), then lo/hi pairs.
    private static final Object[][] SCRIPTS = {
        {"LATIN|LATN", 0x41, 0x5A, 0x61, 0x7A, 0xAA, 0xAA, 0xBA, 0xBA, 0xC0, 0xD6, 0xD8, 0xF6, 0xF8, 0x2B8,
            0x1E00, 0x1EFF, 0x2C60, 0x2C7F, 0xA720, 0xA7FF, 0xFF21, 0xFF3A, 0xFF41, 0xFF5A},
        {"GREEK|GREK", 0x370, 0x373, 0x375, 0x377, 0x37A, 0x37D, 0x37F, 0x3E1, 0x3F0, 0x3FF, 0x1F00, 0x1FFF},
        {"CYRILLIC|CYRL", 0x400, 0x52F, 0x1D2B, 0x1D2B, 0x2DE0, 0x2DFF, 0xA640, 0xA69F},
        {"ARMENIAN|ARMN", 0x531, 0x58F}, {"HEBREW|HEBR", 0x591, 0x5F4},
        {"ARABIC|ARAB", 0x600, 0x6FF, 0x750, 0x77F}, {"DEVANAGARI|DEVA", 0x900, 0x97F},
        {"THAI", 0xE01, 0xE5B}, {"HIRAGANA|HIRA", 0x3041, 0x309F},
        {"KATAKANA|KANA", 0x30A1, 0x30FF, 0x31F0, 0x31FF, 0xFF66, 0xFF9F},
        {"HANGUL|HANG", 0x1100, 0x11FF, 0x3130, 0x318F, 0xAC00, 0xD7AF},
        {"HAN|HANI", 0x2E80, 0x2FDF, 0x3005, 0x3005, 0x3007, 0x3007, 0x3021, 0x3029, 0x3038, 0x303B,
            0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xF900, 0xFAFF, 0x20000, 0x2FA1F},
    };

    private Blocks() {
    }

    /** Upper-cases `name` and drops spaces, underscores and hyphens, as block and script lookup does. */
    private static String normalize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != ' ' && c != '_' && c != '-') {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    static CharClass block(String name) {
        String n = normalize(name);
        for (Object[] b : BLOCKS) {
            if (("|" + b[0] + "|").indexOf("|" + n + "|") >= 0) {
                return CharClass.range((Integer) b[1], (Integer) b[2]);
            }
        }
        return null;
    }

    static CharClass script(String name) {
        String n = normalize(name);
        for (Object[] s : SCRIPTS) {
            if (("|" + s[0] + "|").indexOf("|" + n + "|") >= 0) {
                CharClass result = null;
                for (int i = 1; i < s.length; i += 2) {
                    CharClass r = CharClass.range((Integer) s[i], (Integer) s[i + 1]);
                    result = result == null ? r : CharClass.or(result, r);
                }
                return CharClass.cached(result);
            }
        }
        return null;
    }
}
