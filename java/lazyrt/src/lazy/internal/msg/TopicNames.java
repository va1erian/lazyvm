package lazy.internal.msg;

/**
 * Topic names and filters: {@code /}-separated segments, where in a filter {@code +} matches one
 * segment and a trailing {@code #} the rest (LazyOS docs/midl.md, "Topics"). The generated topic
 * accessors build both from a declared pattern and the program's placeholder values.
 */
public final class TopicNames {
    private TopicNames() {}

    /** Whether the concrete {@code topic} matches {@code filter}. */
    public static boolean matches(String filter, String topic) {
        String[] f = filter.split("/", -1);
        String[] t = topic.split("/", -1);
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals("#") && i == f.length - 1) return true;  // zero or more segments
            if (i >= t.length) return false;
            if (!f[i].equals("+") && !f[i].equals(t[i])) return false;
        }
        return f.length == t.length;
    }

    /** The concrete topic of {@code pattern} with every wildcard given, in order. */
    public static String name(String pattern, String... values) {
        return fill(pattern, values, false);
    }

    /** A filter of {@code pattern}: a null value keeps its wildcard. */
    public static String filter(String pattern, String... values) {
        return fill(pattern, values, true);
    }

    private static String fill(String pattern, String[] values, boolean nullIsWild) {
        String[] parts = pattern.split("/", -1);
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("+") || part.equals("#")) {
                String value = values[next++];
                if (value == null && !nullIsWild) throw new IllegalArgumentException("topic segment " + next + " is null");
                if (value != null) check(value, part.equals("#"));
                part = value == null ? part : value;
            }
            if (i > 0) out.append('/');
            out.append(part);
        }
        return out.toString();
    }

    /**
     * A value for one wildcard, as LazyOS's broker accepts it: segments of letters, digits, {@code
     * _}, {@code .} and {@code -}, none empty or dots only, and one segment for {@code +}.
     */
    private static void check(String value, boolean tail) {
        String[] segments = value.split("/", -1);
        if (!tail && segments.length != 1) throw bad(value);
        for (String s : segments) {
            if (s.isEmpty() || s.replace(".", "").isEmpty()) throw bad(value);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '.' || c == '-';
                if (!ok) throw bad(value);
            }
        }
    }

    private static IllegalArgumentException bad(String value) {
        return new IllegalArgumentException("not a topic segment: \"" + value + "\"");
    }
}
