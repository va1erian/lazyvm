package java.util.regex;

/**
 * Shortcuts for the loop that tries every start position of a find(). Both leave every result a
 * caller can see unchanged: the match found, its groups, hitEnd, requireEnd and the end of the
 * previous match after a failure (Matcher.iterationEnd).
 *
 * <ul>
 * <li><b>First character.</b> When the pattern must begin with one class, one literal, or a
 * repeat of a class that needs at least one char, a start whose char is not in it fails at once.
 * Such a failed attempt sets no flag and records no event, so it is skipped without being run.
 * <li><b>Rest of a run.</b> When the pattern is an unbounded repeat of one class (no group)
 * followed only by literals, classes, line breaks and anchors, a failed attempt at s has already
 * tried the rest of the pattern at every position from s to the end E of the run of that class.
 * An attempt at a later start inside the run (or at E, with nothing to repeat) tries a subset of
 * those positions with the same rest of the pattern, which does not depend on where the match
 * started, so it fails too. It would also set the same flags (only the positions tried before
 * are tried again) and leave the same event, E, so the search resumes after E. The rest of the
 * pattern must be "quiet": no repeat, loop or group that could record an event of its own.
 * </ul>
 */
final class SearchHints {
    private final CharClass startClass;
    private final int startChar;
    private final CharClass run;

    private SearchHints(CharClass startClass, int startChar, CharClass run) {
        this.startClass = startClass;
        this.startChar = startChar;
        this.run = run;
    }

    /** The hints for a parsed pattern, or null when there are none. */
    static SearchHints of(Node tree) {
        Node first = tree;
        boolean quiet = false;
        if (tree instanceof Node.Seq) {
            Node[] items = ((Node.Seq) tree).items;
            first = items[0];
            quiet = true;
            for (int k = 1; k < items.length; k++) {
                quiet &= isQuiet(items[k]);
            }
        }
        CharClass startClass = mustStartWith(tree);
        int startChar = startClass == null && first instanceof Atoms.Str ? ((Atoms.Str) first).chars[0] : -1;
        CharClass run = null;
        if (first instanceof Repeat.CharRepeat) {
            Repeat.CharRepeat r = (Repeat.CharRepeat) first;
            if (quiet && r.group < 0 && r.maxCount() == Node.UNBOUNDED) {
                run = r.cc;
            }
        }
        if (startClass == null && startChar < 0 && run == null) {
            return null;
        }
        return new SearchHints(startClass, startChar, run);
    }

    /**
     * A class that every match of n begins with: n starts with one class, a literal or a repeat
     * that needs a char. An attempt at a char outside it fails at once without any effect.
     */
    static CharClass mustStartWith(Node n) {
        Node first = n instanceof Node.Seq ? ((Node.Seq) n).items[0] : n;
        if (first instanceof Atoms.One) {
            return ((Atoms.One) first).cc;
        }
        if (first instanceof Repeat.CharRepeat && ((Repeat.CharRepeat) first).minCount() > 0) {
            return ((Repeat.CharRepeat) first).cc;
        }
        if (first instanceof GroupNodes.Group) {
            return mustStartWith(((GroupNodes.Group) first).body());
        }
        return null;
    }

    private static boolean isQuiet(Node n) {
        return n instanceof Atoms.One || n instanceof Atoms.Str || n instanceof Atoms.LineBreak || n instanceof Anchor;
    }

    /** False when an attempt at s is sure to fail at once without any effect. */
    boolean canStart(Matcher m, int s) {
        if (s >= m.hi) {
            return true;
        }
        if (startChar >= 0) {
            return m.text.charAt(s) == startChar;
        }
        return startClass == null || startClass.test(Atoms.codePointAt(m, s));
    }

    /** The last start that the failed attempt at s makes pointless. */
    int skipTo(Matcher m, int s) {
        if (run == null) {
            return s;
        }
        int j = s;
        while (j < m.hi) {
            int w = Repeat.widthAt(m, run, j);
            if (w == 0) {
                break;
            }
            if (w == 2) {
                // A start between the halves of a pair reads the run differently, so no start of
                // this run is known to fail.
                return s;
            }
            j += w;
        }
        return j;
    }
}
