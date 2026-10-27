package java.util.regex;

/** An immutable copy of one match, as toMatchResult returns. */
final class MatchSnapshot implements MatchResult {
    private final String pattern;
    private final String input;
    private final int regionStart;
    private final int regionEnd;
    private final int[] spans;
    private final int first;

    MatchSnapshot(Matcher m) {
        this.pattern = m.parentPattern.pattern();
        this.input = m.text.toString();
        this.regionStart = m.regionStart;
        this.regionEnd = m.regionEnd;
        this.spans = (int[]) m.groups.clone();
        this.first = m.first;
    }

    public int start() {
        return start(0);
    }

    public int start(int group) {
        checkSpan(group);
        return spans[2 * group];
    }

    public int end() {
        return end(0);
    }

    public int end(int group) {
        checkSpan(group);
        return spans[2 * group + 1];
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        if (first < 0) {
            throw new IllegalStateException("No match found");
        }
        checkGroup(group);
        if (spans[2 * group] == -1) {
            return null;
        }
        return input.substring(spans[2 * group], spans[2 * group + 1]);
    }

    public int groupCount() {
        return spans.length / 2 - 1;
    }

    private void checkSpan(int group) {
        if (first < 0) {
            throw new IllegalStateException("No match available");
        }
        checkGroup(group);
    }

    private void checkGroup(int group) {
        if (group < 0 || group > groupCount()) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("java.util.regex.Matcher");
        sb.append("[pattern=").append(pattern).append(" region=");
        sb.append(regionStart).append(',').append(regionEnd).append(" lastmatch=");
        if (first >= 0 && spans[0] != -1 && spans[1] != -1) {
            sb.append(input.substring(spans[0], spans[1]));
        }
        return sb.append(']').toString();
    }
}
