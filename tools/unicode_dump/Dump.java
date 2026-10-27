import java.io.*;

public class Dump {
    static int bad;

    static void check(boolean ok, String what, int cp) {
        if (!ok) {
            bad++;
            if (bad <= 20) System.err.println("mismatch " + what + " U+" + Integer.toHexString(cp));
        }
    }

    // ---- final sigma. The oracle's answer depends on neighbours, so it is probed as two small
    // automata: reading the text before a sigma left to right (BEFORE), and the text after it (AFTER).
    // A state is identified by how it answers a set of continuation strings; for each code point we
    // find which state each of the representative states moves to.
    static final String SIGMA = "Σ";
    static final String[] BREP = {"", "A", "\u2160", "1", "A'", "\u2160'", "\u00aa", " "};
    static final String[] BSUF = {"", "1", "\u0345'1", "'", "-", "A", "\u0301", "\u2160", "\u0345", "\u00aa", "\u4e2d", ".", " ", "\u0345\u0301",
        "\ud801\udc28", "\ud835\udfce", "\ud840\udc00", "\ud801\udc00\u0345", "\u00aa\u0345", "\ud801\udc28\u0345", "\ud840\udc00\u0345", "\ud835\udfce\u0345"};
    static final String[] AREP = {"", "A", "1", " ", "'", "1'"};
    static final String[] ASUF = {"", "A", "\u2160", "'A", "-A", "1", "\u0345", "'", "-", "\u00aa", "\u00aaA", "\u4e2d", ".", " ", "\u0301", "\u0345A",
        "\ud801\udc28", "\ud835\udfce", "\ud840\udc00", "\ud801\udc00", "'\ud801\udc28", "1\ud801\udc28", "\ud835\udfce\ud801\udc28", "'\ud835\udfce"};

    // Is the sigma at the end of x final?
    static boolean before(String x) {
        return (x + SIGMA).toLowerCase().endsWith("ς");
    }

    // Is the sigma that follows "A" final when x comes after it?
    static boolean after(String x) {
        return ("A" + SIGMA + x).toLowerCase().charAt(1) == 'ς';
    }

    static int bsig(String x) {
        int s = 0;
        for (int i = 0; i < BSUF.length; i++) if (before(x + BSUF[i])) s |= 1 << i;
        return s;
    }

    static int asig(String x) {
        int s = 0;
        for (int i = 0; i < ASUF.length; i++) if (after(x + ASUF[i])) s |= 1 << i;
        return s;
    }

    static int[] BSTATE = new int[BREP.length], ASTATE = new int[AREP.length];

    static void initStates() {
        for (int i = 0; i < BREP.length; i++) BSTATE[i] = bsig(BREP[i]);
        for (int i = 0; i < AREP.length; i++) ASTATE[i] = asig(AREP[i]);
        for (int i = 0; i < BREP.length; i++)
            for (int j = 0; j < i; j++) check(BSTATE[i] != BSTATE[j], "before states equal", i);
        for (int i = 0; i < AREP.length; i++)
            for (int j = 0; j < i; j++) check(ASTATE[i] != ASTATE[j], "after states equal", i);
    }

    static int indexOf(int[] states, int sig) {
        for (int i = 0; i < states.length; i++) if (states[i] == sig) return i;
        return -1;
    }

    // Where each representative state goes on reading cp.
    static byte[] sigmaColumns(int cp) {
        byte[] out = new byte[BREP.length + AREP.length];
        String c = new String(Character.toChars(cp));
        for (int i = 0; i < BREP.length; i++) {
            int at = indexOf(BSTATE, bsig(BREP[i] + c));
            check(at >= 0, "unknown before state", cp);
            out[i] = (byte) Math.max(at, 0);
        }
        for (int i = 0; i < AREP.length; i++) {
            int at = indexOf(ASTATE, asig(AREP[i] + c));
            check(at >= 0, "unknown after state", cp);
            out[BREP.length + i] = (byte) Math.max(at, 0);
        }
        return out;
    }

    static int preds(int cp) {
        boolean[] p = {
            Character.isWhitespace(cp), Character.isSpaceChar(cp), Character.isMirrored(cp),
            Character.isIdeographic(cp), Character.isAlphabetic(cp), Character.isLetter(cp),
            Character.isDigit(cp), Character.isLetterOrDigit(cp), Character.isUpperCase(cp),
            Character.isLowerCase(cp), Character.isTitleCase(cp),
            Character.isJavaIdentifierStart(cp), Character.isJavaIdentifierPart(cp),
            Character.isUnicodeIdentifierStart(cp), Character.isUnicodeIdentifierPart(cp),
            Character.isIdentifierIgnorable(cp), Character.isISOControl(cp), Character.isDefined(cp)};
        int w = 0;
        for (int i = 0; i < p.length; i++) if (p[i]) w |= 1 << i;
        return w;
    }

    static void record(DataOutputStream o, int cp) throws IOException {
        byte[] columns = cp >= 0 && cp <= 0x10FFFF ? sigmaColumns(cp) : new byte[BREP.length + AREP.length];
        o.writeByte(Character.getType(cp));
        o.writeByte(Character.getDirectionality(cp));
        o.writeByte(Character.digit(cp, 36));
        o.writeInt(Character.getNumericValue(cp));
        o.writeInt(Character.toUpperCase(cp));
        o.writeInt(Character.toLowerCase(cp));
        o.writeInt(Character.toTitleCase(cp));
        o.writeInt(preds(cp));
        o.write(columns);
        if (cp >= 0 && cp <= 0x10FFFF) COLUMNS[cp] = columns;
    }

    static byte[][] COLUMNS = new byte[0x110000][];

    // digit(cp, radix) must be the radix-36 value when it is below the radix; the char
    // overloads must agree with the int ones.
    static void consistency(int cp) {
        int d = Character.digit(cp, 36);
        for (int r = 2; r <= 36; r++) check(Character.digit(cp, r) == (d >= 0 && d < r ? d : -1), "digit", cp);
        for (int r : new int[] {-1, 0, 1, 37, 99}) check(Character.digit(cp, r) == -1, "badradix", cp);
        if (cp > 0xFFFF) return;
        char c = (char) cp;
        check(Character.getType(c) == Character.getType(cp), "getType", cp);
        check(Character.toUpperCase(c) == (char) Character.toUpperCase(cp), "upper", cp);
        check(Character.toLowerCase(c) == (char) Character.toLowerCase(cp), "lower", cp);
        check(Character.toTitleCase(c) == (char) Character.toTitleCase(cp), "title", cp);
        check(Character.getNumericValue(c) == Character.getNumericValue(cp), "numeric", cp);
        check(Character.getDirectionality(c) == Character.getDirectionality(cp), "dir", cp);
        boolean[] a = {Character.isLetter(c), Character.isDigit(c), Character.isWhitespace(c),
            Character.isUpperCase(c), Character.isLowerCase(c), Character.isTitleCase(c),
            Character.isSpaceChar(c), Character.isMirrored(c), Character.isDefined(c),
            Character.isJavaIdentifierStart(c), Character.isJavaIdentifierPart(c),
            Character.isUnicodeIdentifierStart(c), Character.isUnicodeIdentifierPart(c),
            Character.isIdentifierIgnorable(c), Character.isISOControl(c),
            Character.isLetterOrDigit(c)};
        boolean[] b = {Character.isLetter(cp), Character.isDigit(cp), Character.isWhitespace(cp),
            Character.isUpperCase(cp), Character.isLowerCase(cp), Character.isTitleCase(cp),
            Character.isSpaceChar(cp), Character.isMirrored(cp), Character.isDefined(cp),
            Character.isJavaIdentifierStart(cp), Character.isJavaIdentifierPart(cp),
            Character.isUnicodeIdentifierStart(cp), Character.isUnicodeIdentifierPart(cp),
            Character.isIdentifierIgnorable(cp), Character.isISOControl(cp),
            Character.isLetterOrDigit(cp)};
        for (int i = 0; i < a.length; i++) check(a[i] == b[i], "char overload " + i, cp);
    }

    // Characters whose whole-string case mapping differs from the simple one.
    static int specials(DataOutputStream o, boolean upper) throws IOException {
        java.util.List<int[]> found = new java.util.ArrayList<>();
        for (int cp = 0; cp < 0x110000; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            String s = new String(Character.toChars(cp));
            int[] full = (upper ? s.toUpperCase() : s.toLowerCase()).codePoints().toArray();
            int simple = upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
            if (full.length != 1 || full[0] != simple) {
                int[] e = new int[full.length + 1];
                e[0] = cp;
                System.arraycopy(full, 0, e, 1, full.length);
                found.add(e);
            }
        }
        o.writeInt(found.size());
        for (int[] e : found) {
            o.writeInt(e.length - 1);
            for (int v : e) o.writeInt(v);
        }
        return found.size();
    }

    // A deterministic fuzz: random strings of code points from every sigma behaviour, with the
    // oracle's answer for each sigma in them (bit i set when code point i is a sigma lowered to
    // the final form).
    static int fuzz(DataOutputStream o) throws IOException {
        java.util.Map<String, java.util.List<Integer>> groups = new java.util.TreeMap<>();
        for (int cp = 0; cp < 0x110000; cp++) {
            if (cp == 0x130) continue;
            String key = new String(COLUMNS[cp], java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!groups.containsKey(key)) groups.put(key, new java.util.ArrayList<Integer>());
            groups.get(key).add(cp);
        }
        java.util.List<Integer> pool = new java.util.ArrayList<>();
        for (java.util.List<Integer> g : groups.values()) {
            int step = Math.max(1, g.size() / 16);
            for (int i = 0; i < g.size(); i += step) pool.add(g.get(i));
        }
        int[] fixed = {0x3A3, 0x3C2, 0x3C3, 'A', 'a', '1', ' ', '\'', '.', '-', ':', 0xAD, 0x301, 0x345,
            0x2160, 0x4E2D, 0x10400, 0x10428, 0x1D7CE, 0xD801, 0xDC00};
        java.util.Random rnd = new java.util.Random(20241027L);
        int count = 20000;
        o.writeInt(count);
        for (int k = 0; k < count; k++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(10);
            for (int i = 0; i < n; i++) {
                int cp = rnd.nextInt(3) == 0 ? pool.get(rnd.nextInt(pool.size())) : fixed[rnd.nextInt(fixed.length)];
                sb.appendCodePoint(rnd.nextInt(8) == 0 ? 0x3A3 : cp);
            }
            String s = sb.toString(), lower = s.toLowerCase();
            check(lower.length() == s.length(), "fuzz length", k);
            int[] cps = s.codePoints().toArray();
            int mask = 0, at = 0;
            for (int i = 0; i < cps.length; i++) {
                if (cps[i] == 0x3A3 && lower.charAt(at) == 'ς') mask |= 1 << i;
                at += Character.charCount(cps[i]);
            }
            o.writeInt(cps.length);
            for (int cp : cps) o.writeInt(cp);
            o.writeInt(mask);
        }
        return count;
    }

    public static void main(String[] args) throws Exception {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[0]), 1 << 20));
        o.writeInt(0x4C5A4450);
        o.writeUTF(System.getProperty("java.version"));
        initStates();
        o.writeInt(BREP.length);
        o.writeInt(AREP.length);
        for (int sig : BSTATE) o.writeInt(sig);
        for (int sig : ASTATE) o.writeInt(sig);
        for (int cp = 0; cp < 0x110000; cp++) {
            record(o, cp);
            consistency(cp);
        }
        int[] probes = {-1, -2147483648, 0x110000, 0x7FFFFFFF, 0x1FFFFF};
        o.writeInt(probes.length);
        for (int cp : probes) {
            o.writeInt(cp);
            record(o, cp);
            for (int r = 2; r <= 36; r++) check(Character.digit(cp, r) == -1, "probe digit", cp);
        }
        int nu = specials(o, true), nl = specials(o, false);
        fuzz(o);
        o.writeInt(bad);
        o.close();
        System.out.println("java " + System.getProperty("java.version") + ", mismatches: " + bad
            + ", special mappings: upper " + nu + ", lower " + nl);
    }
}
