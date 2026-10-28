package java.util;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;

/**
 * What Scanner.match() returns (docs/design/util-text.md SC4, SC5, SC7): the groups of one match,
 * copied, with positions counted from the start of the whole input rather than of the buffer.
 */
final class ScannerMatch implements MatchResult {
    private final int[] spans;
    private final String[] texts;

    private ScannerMatch(int groups) {
        spans = new int[2 * groups];
        texts = new String[groups];
    }

    /** The current match of `m` over a buffer whose first char is input char `base`. */
    static ScannerMatch of(Matcher m, long base) {
        ScannerMatch r = new ScannerMatch(m.groupCount() + 1);
        for (int g = 0; g < r.texts.length; g++) {
            int s = m.start(g);
            r.spans[2 * g] = s < 0 ? -1 : (int) (s + base);
            r.spans[2 * g + 1] = s < 0 ? -1 : (int) (m.end(g) + base);
            r.texts[g] = m.group(g);
        }
        return r;
    }

    /** A whole token at input position `start`, with no groups of its own (SC7). */
    static ScannerMatch token(String text, long start) {
        ScannerMatch r = new ScannerMatch(1);
        r.spans[0] = (int) start;
        r.spans[1] = (int) (start + text.length());
        r.texts[0] = text;
        return r;
    }

    private void check(int group) {
        if (group < 0 || group >= texts.length) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
    }

    public int start() {
        return start(0);
    }

    public int start(int group) {
        check(group);
        return spans[2 * group];
    }

    public int end() {
        return end(0);
    }

    public int end(int group) {
        check(group);
        return spans[2 * group + 1];
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        check(group);
        return texts[group];
    }

    public int groupCount() {
        return texts.length - 1;
    }
}
