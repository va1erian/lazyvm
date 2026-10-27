package java.util.regex;

/**
 * Unicode general categories for \p{..} classes, numbered as
 * Character.getType numbers them.
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
        if (name.equals("LD")) {
            return mask("L") | bit(Nd);
        }
        if (name.equals("all")) {
            return -1;
        }
        if (name.equals("C")) {
            // Observed on the oracle: C is the four "other" categories that have members
            // (Cc, Cf, Co, Cs) and leaves out unassigned code points (Cn). Pinned by
            // RegexNames (\p{C} against \p{Cn}); mutation: let C include Cn.
            return mask("Cc") | mask("Cf") | mask("Co") | mask("Cs");
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
        return Character.getType(cp);
    }
}
