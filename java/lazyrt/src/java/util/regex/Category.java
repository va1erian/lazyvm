package java.util.regex;

/**
 * Unicode general categories for \p{..} classes.
 *
 * <p>The numeric codes are the ones java.lang.Character.getType uses. Until
 * lazyrt's Character exposes getType (S5-08), {@link #type} derives the
 * category from Character's boolean tests plus exact ASCII/Latin-1 tables and
 * a few block-level rules, so it is exact below U+0100 and approximate above
 * it (docs/regex.md). Replacing the body of {@link #type} with
 * Character.getType makes every category class exact.
 */
final class Category {
    static final int Cn = 0, Lu = 1, Ll = 2, Lt = 3, Lm = 4, Lo = 5, Mn = 6, Me = 7, Mc = 8;
    static final int Nd = 9, Nl = 10, No = 11, Zs = 12, Zl = 13, Zp = 14, Cc = 15, Cf = 16;
    static final int Co = 18, Cs = 19, Pd = 20, Ps = 21, Pe = 22, Pc = 23, Po = 24, Sm = 25;
    static final int Sc = 26, Sk = 27, So = 28, Pi = 29, Pf = 30;

    private static final String[] NAMES = {"Cn", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc",
        "Nd", "Nl", "No", "Zs", "Zl", "Zp", "Cc", "Cf", null, "Co", "Cs", "Pd", "Ps", "Pe", "Pc",
        "Po", "Sm", "Sc", "Sk", "So", "Pi", "Pf"};

    private Category() {
    }

    /** The bit mask of categories named by `name` ("L", "Lu", "LC", ...), or 0 when unknown. */
    static int mask(String name) {
        if (name.equals("LC")) {
            return bit(Lu) | bit(Ll) | bit(Lt);
        }
        if (name.length() == 1) {
            int mask = 0;
            for (int t = 0; t < NAMES.length; t++) {
                if (NAMES[t] != null && NAMES[t].charAt(0) == name.charAt(0)) {
                    mask |= bit(t);
                }
            }
            return mask;
        }
        for (int t = 0; t < NAMES.length; t++) {
            if (name.equals(NAMES[t])) {
                return bit(t);
            }
        }
        return 0;
    }

    static int bit(int type) {
        return 1 << type;
    }

    static int type(int cp) {
        if (cp < 0x80) {
            return ascii(cp);
        }
        if (cp < 0x100) {
            return latin1(cp);
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return Cs;
        }
        if ((cp >= 0xE000 && cp <= 0xF8FF) || (cp >= 0xF0000 && cp <= 0x10FFFF)) {
            return (cp & 0xFFFE) == 0xFFFE ? Cn : Co;
        }
        if (Character.isLetter(cp)) {
            return letter(cp);
        }
        if (Character.isDigit(cp)) {
            return Nd;
        }
        return other(cp);
    }

    private static int ascii(int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return Cc;
        }
        if (cp >= '0' && cp <= '9') {
            return Nd;
        }
        if (cp >= 'A' && cp <= 'Z') {
            return Lu;
        }
        if (cp >= 'a' && cp <= 'z') {
            return Ll;
        }
        switch (cp) {
            case ' ': return Zs;
            case '$': return Sc;
            case '(': case '[': case '{': return Ps;
            case ')': case ']': case '}': return Pe;
            case '+': case '<': case '=': case '>': case '|': case '~': return Sm;
            case '-': return Pd;
            case '^': case '`': return Sk;
            case '_': return Pc;
            default: return Po;
        }
    }

    private static int latin1(int cp) {
        if (cp < 0xA0) {
            return Cc;
        }
        if (cp >= 0xC0) {
            if (cp == 0xD7 || cp == 0xF7) {
                return Sm;
            }
            return cp <= 0xDE ? Lu : Ll;
        }
        switch (cp) {
            case 0xA0: return Zs;
            case 0xA2: case 0xA3: case 0xA4: case 0xA5: return Sc;
            case 0xA6: case 0xA9: case 0xAE: case 0xB0: return So;
            case 0xA8: case 0xAF: case 0xB4: case 0xB8: return Sk;
            case 0xAA: case 0xBA: return Lo;
            case 0xAB: return Pi;
            case 0xBB: return Pf;
            case 0xAC: case 0xB1: return Sm;
            case 0xAD: return Cf;
            case 0xB2: case 0xB3: case 0xB9: case 0xBC: case 0xBD: case 0xBE: return No;
            case 0xB5: return Ll;
            default: return Po;
        }
    }

    private static int letter(int cp) {
        if (Character.isUpperCase(cp)) {
            return Lu;
        }
        if (Character.isLowerCase(cp)) {
            return Ll;
        }
        if (isTitle(cp)) {
            return Lt;
        }
        boolean modifier = (cp >= 0x2B0 && cp <= 0x2C1) || (cp >= 0x2C6 && cp <= 0x2D1)
            || (cp >= 0x2E0 && cp <= 0x2E4) || cp == 0x2EC || cp == 0x2EE || cp == 0x374
            || cp == 0x37A || cp == 0x559 || cp == 0x640 || cp == 0x3005
            || (cp >= 0x3031 && cp <= 0x3035) || cp == 0x309D || cp == 0x309E
            || (cp >= 0x30FC && cp <= 0x30FE) || cp == 0xFF70;
        return modifier ? Lm : Lo;
    }

    private static boolean isTitle(int cp) {
        return cp == 0x1C5 || cp == 0x1C8 || cp == 0x1CB || cp == 0x1F2
            || (cp >= 0x1F88 && cp <= 0x1F8F) || (cp >= 0x1F98 && cp <= 0x1F9F)
            || (cp >= 0x1FA8 && cp <= 0x1FAF) || cp == 0x1FBC || cp == 0x1FCC || cp == 0x1FFC;
    }

    private static int other(int cp) {
        if (Character.isSpaceChar(cp)) {
            return cp == 0x2028 ? Zl : (cp == 0x2029 ? Zp : Zs);
        }
        if ((cp >= 0x600 && cp <= 0x603) || cp == 0x6DD || cp == 0x70F
            || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E)
            || (cp >= 0x2060 && cp <= 0x2064) || cp == 0xFEFF || (cp >= 0xFFF9 && cp <= 0xFFFB)) {
            return Cf;
        }
        if ((cp >= 0x300 && cp <= 0x36F) || (cp >= 0x483 && cp <= 0x487)
            || (cp >= 0x1DC0 && cp <= 0x1DFF) || (cp >= 0x20D0 && cp <= 0x20DC) || cp == 0x20E1
            || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xFE20 && cp <= 0xFE26)) {
            return Mn;
        }
        if (cp == 0x488 || cp == 0x489 || (cp >= 0x20DD && cp <= 0x20E0)) {
            return Me;
        }
        return symbolOrPunct(cp);
    }

    private static int symbolOrPunct(int cp) {
        if (cp >= 0x2010 && cp <= 0x2015) {
            return Pd;
        }
        if (cp == 0x2018 || cp == 0x201B || cp == 0x201C || cp == 0x201F || cp == 0x2039) {
            return Pi;
        }
        if (cp == 0x2019 || cp == 0x201D || cp == 0x203A) {
            return Pf;
        }
        if (cp == 0x201A || cp == 0x201E) {
            return Ps;
        }
        if ((cp >= 0x2016 && cp <= 0x2027) || (cp >= 0x2030 && cp <= 0x205E && cp != 0x2044 && cp != 0x2052)
            || cp == 0x3001 || cp == 0x3002 || cp == 0x3003) {
            return Po;
        }
        if (cp >= 0x20A0 && cp <= 0x20B9) {
            return Sc;
        }
        if (cp == 0x2044 || cp == 0x2052 || (cp >= 0x2200 && cp <= 0x22FF) || (cp >= 0x2190 && cp <= 0x2194)
            || (cp >= 0x2980 && cp <= 0x2AFF) || cp == 0x3012) {
            return Sm;
        }
        if ((cp >= 0x2150 && cp <= 0x215F) || (cp >= 0x2460 && cp <= 0x249B)
            || (cp >= 0x2070 && cp <= 0x2079) || (cp >= 0x2080 && cp <= 0x2089)) {
            return No;
        }
        if (cp >= 0x2160 && cp <= 0x2182) {
            return Nl;
        }
        if ((cp >= 0x2195 && cp <= 0x21FF) || (cp >= 0x2300 && cp <= 0x23FF) || (cp >= 0x2400 && cp <= 0x2426)
            || (cp >= 0x2500 && cp <= 0x27BF) || (cp >= 0x2800 && cp <= 0x28FF)
            || (cp >= 0x1F300 && cp <= 0x1F64F)) {
            return So;
        }
        return Cn;
    }
}
