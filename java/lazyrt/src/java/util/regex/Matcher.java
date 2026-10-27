package java.util.regex;

/**
 * Matches a {@link Pattern} against a text. Search state lives here, so the
 * pattern's nodes stay immutable and shareable.
 */
public final class Matcher implements MatchResult {
    Pattern parentPattern;
    CharSequence text;
    int textLength;

    /** Capture spans: start/end per group, -1 when unset. Index 0 is the whole match. */
    int[] groups;
    /** Where each open group started. */
    int[] open;
    /** Per-loop iteration counters (see Repeat.Loop). */
    int[] loopCount;
    /** Per-look-around indexes of the text (see StartIndex), made when first needed. */
    StartIndex[] starts;
    int[] loopStart;

    /** The region, and the bounds nodes may read within (lookaround can widen them temporarily). */
    int regionStart;
    int regionEnd;
    int lo;
    int hi;
    boolean transparentBounds;
    boolean anchoringBounds = true;

    int first = -1;
    int last;
    /** The end of the previous match, for \G. */
    int oldLast = -1;
    int lastAppendPosition;

    boolean hitEnd;
    boolean requireEnd;
    boolean mustReachEnd;
    int matchEnd;
    int stopPos;
    /** Where the most recent repeat iteration ended in the current attempt, or -1. */
    int iterationEnd = -1;
    /** Failed loop states of the current search (see LoopMemo). */
    final LoopMemo memo = new LoopMemo();
    int behindTarget;

    Matcher(Pattern parent, CharSequence text) {
        this.parentPattern = parent;
        this.text = text;
        allocate();
        reset();
    }

    private void allocate() {
        groups = new int[2 * (parentPattern.groupCount + 1)];
        open = new int[parentPattern.groupCount + 1];
        loopCount = new int[parentPattern.loopCount];
        loopStart = new int[parentPattern.loopCount];
        starts = new StartIndex[parentPattern.lookCount];
        clearGroups();
    }

    public Pattern pattern() {
        return parentPattern;
    }

    public MatchResult toMatchResult() {
        return new MatchSnapshot(this);
    }

    public Matcher usePattern(Pattern newPattern) {
        if (newPattern == null) {
            throw new IllegalArgumentException("Pattern cannot be null");
        }
        parentPattern = newPattern;
        allocate();
        return this;
    }

    public Matcher reset() {
        first = -1;
        last = 0;
        oldLast = -1;
        clearGroups();
        lastAppendPosition = 0;
        textLength = text.length();
        if (!(text instanceof String)) {
            // Only text that can change makes the starts cached for lookbehinds stale; find(int),
            // region and replaceAll reset a matcher over a String again and again.
            starts = new StartIndex[parentPattern.lookCount];
        }
        regionStart = 0;
        regionEnd = textLength;
        return this;
    }

    public Matcher reset(CharSequence input) {
        if (input == null) {
            throw new NullPointerException();
        }
        text = input;
        starts = new StartIndex[parentPattern.lookCount];
        return reset();
    }

    // Results.

    public int start() {
        if (first < 0) {
            throw new IllegalStateException("No match available");
        }
        return first;
    }

    public int start(int group) {
        checkGroup(group);
        return groups[group * 2];
    }

    public int start(String name) {
        return groups[groupIndex(name) * 2];
    }

    public int end() {
        if (first < 0) {
            throw new IllegalStateException("No match available");
        }
        return last;
    }

    public int end(int group) {
        checkGroup(group);
        return groups[group * 2 + 1];
    }

    public int end(String name) {
        return groups[groupIndex(name) * 2 + 1];
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        if (first < 0) {
            throw new IllegalStateException("No match found");
        }
        if (group < 0 || group > groupCount()) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
        if (groups[group * 2] == -1 || groups[group * 2 + 1] == -1) {
            return null;
        }
        return text.subSequence(groups[group * 2], groups[group * 2 + 1]).toString();
    }

    public String group(String name) {
        return group(groupIndex(name));
    }

    public int groupCount() {
        return parentPattern.groupCount;
    }

    private void checkGroup(int group) {
        if (first < 0) {
            throw new IllegalStateException("No match available");
        }
        if (group < 0 || group > groupCount()) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
    }

    private int groupIndex(String name) {
        if (name == null) {
            throw new NullPointerException("Group name");
        }
        if (first < 0) {
            throw new IllegalStateException("No match found");
        }
        int index = parentPattern.groupNumber(name);
        if (index < 0) {
            throw new IllegalArgumentException("No group with name <" + name + ">");
        }
        return index;
    }

    // Searching.

    public boolean matches() {
        return attempt(regionStart, true);
    }

    public boolean lookingAt() {
        return attempt(regionStart, false);
    }

    public boolean find() {
        int from = last;
        if (from == first) {
            from++;
        }
        if (from < regionStart) {
            from = regionStart;
        }
        if (from > regionEnd) {
            clearGroups();
            return false;
        }
        return search(from);
    }

    public boolean find(int start) {
        if (start < 0 || start > text.length()) {
            throw new IndexOutOfBoundsException("Illegal start index");
        }
        reset();
        return search(start);
    }

    private void clearGroups() {
        for (int i = 0; i < groups.length; i++) {
            groups[i] = -1;
        }
    }

    private void begin(int from) {
        hitEnd = false;
        requireEnd = false;
        textLength = text.length();
        lo = regionStart;
        hi = regionEnd;
        oldLast = oldLast < 0 ? from : oldLast;
        iterationEnd = -1;
        memo.reset(regionStart, regionEnd, parentPattern.loopCount);
        clearGroups();
    }

    private boolean search(int from) {
        begin(from);
        mustReachEnd = false;
        if (parentPattern.anchoredStart) {
            if (parentPattern.root.match(this, from)) {
                return succeed(from);
            }
            first = -1;
            oldLast = afterFailure();
            return false;
        }
        int guard = hi - parentPattern.minLength;
        boolean skipsPairs = parentPattern.supplementary;
        SearchHints hints = parentPattern.hints;
        for (int s = from; s <= guard; s++) {
            if (hints == null || hints.canStart(this, s)) {
                if (parentPattern.root.match(this, s)) {
                    return succeed(s);
                }
                if (hints != null) {
                    s = hints.skipTo(this, s);
                }
            }
            // Observed on the oracle: the step reads the pair even when the region end splits it.
            if (skipsPairs && s + 1 < textLength && Character.isSurrogatePair(text.charAt(s), text.charAt(s + 1))) {
                s++;
            }
        }
        hitEnd = true;
        first = -1;
        oldLast = afterFailure();
        return false;
    }

    private boolean attempt(int from, boolean whole) {
        begin(from);
        mustReachEnd = whole;
        first = from;
        if (parentPattern.root.match(this, from)) {
            return succeed(from);
        }
        first = -1;
        oldLast = afterFailure();
        return false;
    }

    /**
     * Observed on the oracle (RegexAfterFailure): a failed attempt leaves the matcher's "end of
     * previous match" where the last recorded event ended (a run of a repeated class, an iteration
     * of a loop over a fixed-width body, the body of an atomic group; see Repeat and GroupNodes),
     * which is where \G matches and where the next find() starts.
     */
    private int afterFailure() {
        if (iterationEnd >= 0) {
            last = iterationEnd;
        }
        return last;
    }

    private boolean succeed(int start) {
        first = start;
        last = matchEnd;
        groups[0] = first;
        groups[1] = last;
        oldLast = last;
        return true;
    }

    // Regions.

    public Matcher region(int start, int end) {
        if (start < 0 || start > text.length()) {
            throw new IndexOutOfBoundsException("start");
        }
        if (end < 0 || end > text.length()) {
            throw new IndexOutOfBoundsException("end");
        }
        if (start > end) {
            throw new IndexOutOfBoundsException("start > end");
        }
        reset();
        regionStart = start;
        regionEnd = end;
        return this;
    }

    public int regionStart() {
        return regionStart;
    }

    public int regionEnd() {
        return regionEnd;
    }

    public boolean hasTransparentBounds() {
        return transparentBounds;
    }

    public Matcher useTransparentBounds(boolean b) {
        transparentBounds = b;
        return this;
    }

    public boolean hasAnchoringBounds() {
        return anchoringBounds;
    }

    public Matcher useAnchoringBounds(boolean b) {
        anchoringBounds = b;
        return this;
    }

    public boolean hitEnd() {
        return hitEnd;
    }

    public boolean requireEnd() {
        return requireEnd;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.regex.Matcher");
        sb.append("[pattern=").append(pattern());
        sb.append(" region=").append(regionStart()).append(',').append(regionEnd());
        sb.append(" lastmatch=");
        if (first >= 0 && group() != null) {
            sb.append(group());
        }
        sb.append(']');
        return sb.toString();
    }

    // Replacement.

    public static String quoteReplacement(String s) {
        if (s.indexOf('\\') == -1 && s.indexOf('$') == -1) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public Matcher appendReplacement(StringBuffer sb, String replacement) {
        if (first < 0) {
            throw new IllegalStateException("No match available");
        }
        String expanded = Replacement.expand(this, replacement);
        if (lastAppendPosition > first) {
            throw new IndexOutOfBoundsException(
                "start " + lastAppendPosition + ", end " + first + ", s.length() " + text.length());
        }
        sb.append(text.subSequence(lastAppendPosition, first).toString());
        sb.append(expanded);
        lastAppendPosition = last;
        return this;
    }

    public StringBuffer appendTail(StringBuffer sb) {
        sb.append(text.subSequence(lastAppendPosition, text.length()).toString());
        return sb;
    }

    public String replaceAll(String replacement) {
        reset();
        boolean result = find();
        if (!result) {
            return text.toString();
        }
        StringBuffer sb = new StringBuffer();
        do {
            appendReplacement(sb, replacement);
            result = find();
        } while (result);
        appendTail(sb);
        return sb.toString();
    }

    public String replaceFirst(String replacement) {
        if (replacement == null) {
            throw new NullPointerException("replacement");
        }
        reset();
        if (!find()) {
            return text.toString();
        }
        StringBuffer sb = new StringBuffer();
        appendReplacement(sb, replacement);
        appendTail(sb);
        return sb.toString();
    }
}
