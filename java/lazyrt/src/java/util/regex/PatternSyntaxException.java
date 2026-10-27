package java.util.regex;

/** Thrown when a regular expression pattern is malformed. */
public class PatternSyntaxException extends IllegalArgumentException {
    private static final long serialVersionUID = -3864639126226059218L;

    private final String desc;
    private final String pattern;
    private final int index;

    public PatternSyntaxException(String desc, String regex, int index) {
        this.desc = desc;
        this.pattern = regex;
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public String getDescription() {
        return desc;
    }

    public String getPattern() {
        return pattern;
    }

    /**
     * Description, then " near index N" when the index is known, then the
     * pattern, then a caret line under the offending character when the index
     * points inside the pattern.
     */
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(desc);
        if (index >= 0) {
            sb.append(" near index ");
            sb.append(index);
        }
        String nl = System.lineSeparator();
        sb.append(nl);
        sb.append(pattern);
        if (index >= 0 && pattern != null && index < pattern.length()) {
            sb.append(nl);
            for (int i = 0; i < index; i++) {
                sb.append(' ');
            }
            sb.append('^');
        }
        return sb.toString();
    }
}
