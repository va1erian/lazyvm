package java.util.regex;

public class PatternSyntaxException extends IllegalArgumentException {
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

    /** The description, the index, the pattern and a caret under the error, on separate lines. */
    public String getMessage() {
        StringBuilder sb = new StringBuilder(desc);
        if (index >= 0) {
            sb.append(" near index ").append(index);
        }
        String nl = System.lineSeparator();
        sb.append(nl).append(pattern);
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
