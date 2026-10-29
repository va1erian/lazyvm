package java.util;

/**
 * BCP 47 language tags for Locale: the lenient reader behind forLanguageTag, the
 * writer behind toLanguageTag, and the readers of the canonical extension text.
 * The behaviour on ill-formed input (keep what parsed before the first bad subtag)
 * and on legacy codes was fixed by probing the oracle (tests/java/util/LocaleTags).
 */
final class LocaleTags {
    // Tags the specification maps to a language (and, for the irregular ones, a private use
    // subtag that records the old tag): whole-tag matches, in lower case.
    private static final String[] LEGACY = {
        "art-lojban", "jbo", "cel-gaulish", "xtg-x-cel-gaulish", "en-gb-oed", "en-GB-x-oed",
        "i-ami", "ami", "i-bnn", "bnn", "i-default", "en-x-i-default", "i-enochian", "x-i-enochian",
        "i-hak", "hak", "i-klingon", "tlh", "i-lux", "lb", "i-mingo", "see-x-i-mingo",
        "i-navajo", "nv", "i-pwn", "pwn", "i-tao", "tao", "i-tay", "tay", "i-tsu", "tsu",
        "no-bok", "nb", "no-nyn", "nn", "sgn-be-fr", "sfb", "sgn-be-nl", "vgt", "sgn-ch-de", "sgg",
        "zh-guoyu", "cmn", "zh-hakka", "hak", "zh-min", "nan-x-zh-min", "zh-min-nan", "nan",
        "zh-xiang", "hsn",
    };

    private final List<String> tags;
    private int at;
    private String language = "";
    private String script = "";
    private String region = "";
    private final StringBuilder variant = new StringBuilder();
    private final TreeMap<Character, String> extensions = new TreeMap<Character, String>();

    private LocaleTags(List<String> tags) {
        this.tags = tags;
    }

    static Locale parse(String tag) {
        if (tag == null) {
            throw new NullPointerException();
        }
        String lower = lower(tag);
        for (int i = 0; i < LEGACY.length; i += 2) {
            if (LEGACY[i].equals(lower)) {
                tag = LEGACY[i + 1];
                break;
            }
        }
        return new LocaleTags(split(tag)).run();
    }

    private Locale run() {
        String first = tags.get(0);
        if (first.equalsIgnoreCase("x")) {
            privateUse();
        } else if (isAlpha(first, 2, 8)) {
            readLanguage();
            if (at < tags.size() && isAlpha(tags.get(at), 4, 4)) {
                String s = lower(tags.get(at++));
                script = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
            if (at < tags.size() && (isAlpha(tags.get(at), 2, 2) || isDigits(tags.get(at), 3))) {
                region = tags.get(at++).toUpperCase(Locale.ROOT);
            }
            while (at < tags.size() && isVariant(tags.get(at))) {
                addVariant(tags.get(at++));
            }
            readExtensions();
            privateUse();
        }
        return build();
    }

    // The first extended language subtag replaces the language; up to three are skipped.
    private void readLanguage() {
        // Only the lower-case spelling means "undetermined"; UND stays a language code.
        language = tags.get(0).equals("und") ? "" : lower(tags.get(0));
        at = 1;
        for (int k = 0; k < 3 && at < tags.size() && isAlpha(tags.get(at), 3, 3); k++) {
            if (k == 0) {
                language = lower(tags.get(at));
            }
            at++;
        }
    }

    private void addVariant(String v) {
        if (variant.length() > 0) {
            variant.append('_');
        }
        variant.append(v);
    }

    /** Singleton extensions; a repeated or empty one ends the tag. */
    private void readExtensions() {
        while (at < tags.size()) {
            String s = tags.get(at);
            if (s.length() != 1 || !isAlpha(s, 1, 1) || s.equalsIgnoreCase("x")) {
                return;
            }
            char key = Character.toLowerCase(s.charAt(0));
            int j = at + 1;
            List<String> values = new ArrayList<String>();
            while (j < tags.size() && isAlnum(tags.get(j), 2, 8)) {
                values.add(lower(tags.get(j++)));
            }
            if (values.isEmpty() || extensions.containsKey(key)) {
                at = tags.size();
                return;
            }
            extensions.put(key, key == 'u' ? canonicalUnicode(values) : join(values, 0, values.size(), "-"));
            at = j;
        }
    }

    // Attributes first, then keywords by key; the first of a repeated key or attribute wins.
    private static String canonicalUnicode(List<String> values) {
        TreeSet<String> attributes = new TreeSet<String>();
        TreeMap<String, String> keywords = new TreeMap<String, String>();
        int k = 0;
        while (k < values.size() && values.get(k).length() >= 3) {
            attributes.add(values.get(k++));
        }
        while (k < values.size()) {
            String key = values.get(k++);
            int from = k;
            while (k < values.size() && values.get(k).length() >= 3) {
                k++;
            }
            if (!keywords.containsKey(key)) {
                keywords.put(key, join(values, from, k, "-"));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String a : attributes) {
            sb.append(sb.length() == 0 ? "" : "-").append(a);
        }
        for (Map.Entry<String, String> e : keywords.entrySet()) {
            sb.append(sb.length() == 0 ? "" : "-").append(e.getKey());
            sb.append(e.getValue().isEmpty() ? "" : "-" + e.getValue());
        }
        return sb.toString();
    }

    /** x-...: subtags after "lvariant" are really variants that are not BCP 47 variants. */
    private void privateUse() {
        if (at >= tags.size() || !tags.get(at).equalsIgnoreCase("x")) {
            return;
        }
        List<String> parts = new ArrayList<String>();
        for (int j = at + 1; j < tags.size() && isAlnum(tags.get(j), 1, 8); j++) {
            parts.add(tags.get(j));
        }
        if (parts.isEmpty()) {
            return;
        }
        int cut = parts.size();
        for (int j = 0; j < parts.size() - 1; j++) {
            if (parts.get(j).equalsIgnoreCase("lvariant")) {
                cut = j;
                break;
            }
        }
        for (int j = cut + 1; j < parts.size(); j++) {
            addVariant(parts.get(j));
        }
        if (cut > 0) {
            extensions.put('x', lower(join(parts, 0, cut, "-")));
        }
    }

    private Locale build() {
        StringBuilder ext = new StringBuilder();
        for (Map.Entry<Character, String> e : extensions.entrySet()) {
            if (e.getKey().charValue() != 'x') {
                ext.append(ext.length() == 0 ? "" : "-").append(e.getKey()).append('-').append(e.getValue());
            }
        }
        if (extensions.containsKey('x')) {
            ext.append(ext.length() == 0 ? "" : "-").append("x-").append(extensions.get('x'));
        }
        return new Locale(language, script, region, variant.toString(), ext.toString());
    }

    // ---- toLanguageTag ----

    static String format(String language, String script, String country, String variant, String ext) {
        if (language.equals("no") && country.equals("NO") && variant.equals("NY")) {
            return "nn-NO";
        }
        StringBuilder sb = new StringBuilder(isAlpha(language, 2, 8) ? newCode(language) : "und");
        if (isAlpha(script, 4, 4)) {
            sb.append('-').append(script);
        }
        if (isAlpha(country, 2, 2) || isDigits(country, 3)) {
            sb.append('-').append(country);
        }
        List<String> parts = split(variant, '_');
        int k = 0;
        while (k < parts.size() && isVariant(parts.get(k))) {
            sb.append('-').append(parts.get(k++));
        }
        int firstOld = k;
        while (k < parts.size() && isAlnum(parts.get(k), 1, 8)) {
            k++;
        }
        String privateUse = privateUse(extension(ext, 'x'), parts.subList(firstOld, k));
        boolean bare = !isAlpha(language, 2, 8) && sb.toString().equals("und");
        String plain = withoutPrivate(ext);
        if (!plain.isEmpty()) {
            sb.append('-').append(plain);
            bare = false;
        }
        if (!privateUse.isEmpty()) {
            return bare ? privateUse : sb.append('-').append(privateUse).toString();
        }
        return sb.toString();
    }

    private static String newCode(String lang) {
        if (lang.equals("iw")) {
            return "he";
        }
        if (lang.equals("ji")) {
            return "yi";
        }
        return lang.equals("in") ? "id" : lang;
    }

    private static String privateUse(String x, List<String> old) {
        if (x == null && old.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("x");
        if (x != null) {
            sb.append('-').append(x);
        }
        if (!old.isEmpty()) {
            sb.append("-lvariant-").append(join(old, 0, old.size(), "-"));
        }
        return sb.toString();
    }

    private static String withoutPrivate(String ext) {
        if (ext.startsWith("x-")) {
            return "";
        }
        int x = ext.indexOf("-x-");
        return x < 0 ? ext : ext.substring(0, x);
    }

    // ---- reading canonical extension text ----

    static String extension(String ext, char key) {
        List<String> sub = split(ext, '-');
        for (int i = 0; i < sub.size(); i++) {
            if (key != 'x' && sub.get(i).equals("x")) {
                return null; // private use runs to the end and holds no extensions
            }
            if (sub.get(i).length() == 1 && sub.get(i).charAt(0) == key) {
                int j = i + 1;
                // Private use may hold one-letter subtags, so it runs to the end.
                while (j < sub.size() && (key == 'x' || sub.get(j).length() > 1)) {
                    j++;
                }
                return join(sub, i + 1, j, "-");
            }
        }
        return null;
    }

    static Set<Character> extensionKeys(String ext) {
        TreeSet<Character> keys = new TreeSet<Character>();
        for (String s : split(ext, '-')) {
            if (s.length() == 1) {
                keys.add(s.charAt(0));
                if (s.charAt(0) == 'x') {
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    static boolean isUnicodeKey(String key) {
        return isAlnum(key, 2, 2);
    }

    static Set<String> unicodeAttributes(String ext) {
        TreeSet<String> out = new TreeSet<String>();
        String u = extension(ext, 'u');
        List<String> sub = u == null ? new ArrayList<String>() : split(u, '-');
        for (int i = 0; i < sub.size() && sub.get(i).length() >= 3; i++) {
            out.add(sub.get(i));
        }
        return Collections.unmodifiableSet(out);
    }

    static Set<String> unicodeKeys(String ext) {
        return Collections.unmodifiableSet(new TreeSet<String>(keywords(ext).keySet()));
    }

    static String unicodeType(String ext, String key) {
        return keywords(ext).get(key);
    }

    private static Map<String, String> keywords(String ext) {
        TreeMap<String, String> out = new TreeMap<String, String>();
        String u = extension(ext, 'u');
        if (u == null) {
            return out;
        }
        List<String> sub = split(u, '-');
        int k = 0;
        while (k < sub.size() && sub.get(k).length() >= 3) {
            k++;
        }
        while (k < sub.size()) {
            String key = sub.get(k++);
            int from = k;
            while (k < sub.size() && sub.get(k).length() >= 3) {
                k++;
            }
            out.put(key, join(sub, from, k, "-"));
        }
        return out;
    }

    // ---- small text helpers ----

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static List<String> split(String s) {
        return split(s, '-');
    }

    /** Every piece between separators, empty ones included; "" gives no pieces for extensions. */
    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<String>();
        if (s.isEmpty() && sep == '_') {
            return out;
        }
        int from = 0;
        for (int i = s.indexOf(sep); i >= 0; i = s.indexOf(sep, from)) {
            out.add(s.substring(from, i));
            from = i + 1;
        }
        out.add(s.substring(from));
        return out;
    }

    private static String join(List<String> parts, int from, int to, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i == from ? "" : sep).append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean isAlnum(String s, int min, int max) {
        if (s.length() < min || s.length() > max) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlpha(String s, int min, int max) {
        if (!isAlnum(s, min, max)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(String s, int len) {
        if (s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    // Five to eight letters or digits, or a digit and three more.
    private static boolean isVariant(String s) {
        return isAlnum(s, 5, 8) || (isAlnum(s, 4, 4) && s.charAt(0) >= '0' && s.charAt(0) <= '9');
    }
}
