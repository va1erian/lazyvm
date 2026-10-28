package java.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The matching side of Scanner (docs/design/util-text.md SC1-SC6): where the next token, line or
 * search result lies in ScannerInput's buffer. It reads more input when the text so far cannot
 * settle an answer, and gives ScannerInput.more a probe so that waiting for a long token or line
 * stays linear (see there). What a match means for match(), the hasNext memory and the position
 * is decided by Scanner.
 */
final class ScannerTokens {
    static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");
    /** SC5: a line with its terminator, or a last line without one. */
    private static final Pattern LINE = Pattern.compile(".*(\r\n|[\n\r  \u0085])|.+$");

    private final ScannerInput in;
    Pattern delimiter = WHITESPACE;
    private Matcher delimiterMatcher;
    /** Where the delimiter before the token that token() last looked at ends. */
    int skipTo;

    ScannerTokens(ScannerInput in) {
        this.in = in;
        lineArrived = (from, to) -> lineSettled(in.lineEnd());
    }

    void useDelimiter(Pattern pattern) {
        delimiter = pattern;
        delimiterMatcher = null;
    }

    private Matcher delimiter(int from, int to) {
        if (delimiterMatcher == null) {
            delimiterMatcher = in.matcher(delimiter, from, to);
        }
        return delimiterMatcher.region(from, to);
    }

    /** How many starts a run probe tries: a periodic run such as (ab)+ is in phase at one of them. */
    private static final int PHASES = 16;

    /**
     * A probe: a match of `p` near `from` that stops before `to`, so a run has ended there. The
     * window may start mid-period of a run like `(ab)+`; the first start where `p` matches at all
     * decides, which costs little when the first one already does.
     */
    private ScannerInput.Probe runEnds(Pattern p) {
        return (from, to) -> {
            for (int k = from; k < Math.min(to, from + PHASES); k++) {
                Matcher m = in.matcher(p, k, to);
                if (m.lookingAt()) {
                    return !m.hitEnd();
                }
                // Failing for want of text usually means the run is still open. Stopping here keeps
                // each probe cheap; a pattern whose other branch runs to the end from a mid-period
                // start, as (ab)+|b[^!]*!, then waits for the next doubling (util-text.md section 4).
                if (m.hitEnd()) {
                    return false;
                }
            }
            return false;
        };
    }

    /**
     * A probe: a match of `p` in the new text, ending after buffer index `after`, that more text
     * cannot undo. `after` is kept as an input position, as the buffer may be compacted between
     * reads.
     */
    private ScannerInput.Probe occurs(Pattern p, int after) {
        long afterAt = after < 0 ? Long.MIN_VALUE : in.at(after);
        return (from, to) -> {
            Matcher m = in.matcher(p, from, to);
            while (m.find()) {
                if (in.at(m.end()) > afterAt) {
                    return !m.requireEnd();
                }
            }
            return false;
        };
    }

    /** A probe: a line terminator has arrived (a final \r waits for a possible \n). */
    private final ScannerInput.Probe lineArrived;

    private boolean lineSettled(int stop) {
        return stop >= 0 && !(stop == in.buf.length() - 1 && in.buf.charAt(stop) == '\r');
    }

    /**
     * SC2: the next token's [start, end) in the buffer, or null when there is none. Either way
     * `skipTo` is where the delimiter before it ends, which a failing next call passes.
     */
    int[] token() {
        while (true) {
            int len = in.buf.length();
            Matcher d = delimiter(in.pos, len);
            boolean skipped = d.lookingAt();
            if (d.hitEnd() && in.more(runEnds(delimiter))) {
                continue;
            }
            int start = skipped ? d.end() : in.pos;
            if (start == len && in.more(runEnds(delimiter))) {
                continue;
            }
            skipTo = start;
            if (start == len) {
                return null;
            }
            d.region(start, len);
            boolean found = false;
            while (d.find()) {
                if (d.end() > start) {
                    found = true;
                    break;
                }
            }
            if ((!found || d.requireEnd()) && in.more(occurs(delimiter, start))) {
                continue;
            }
            return new int[] {start, found ? d.start() : len};
        }
    }

    /** SC5: the next line's matcher, or null when there is no further line. */
    Matcher line() {
        while (true) {
            // A plain scan finds the line end first, so the regex runs once per line.
            if (!lineSettled(in.lineEnd()) && in.more(lineArrived)) {
                continue;
            }
            Matcher m = in.matcher(LINE, in.pos, in.buf.length());
            boolean ok = m.lookingAt();
            if (m.hitEnd() && in.more(null)) {
                continue;
            }
            return ok ? m : null;
        }
    }

    /** SC6: a match before the next line terminator, or null; an empty line has none, not even "". */
    Matcher findInLine(Pattern pattern) {
        while (true) {
            int stop = in.lineEnd();
            if (stop < 0 && in.more(lineArrived)) {
                continue;
            }
            stop = stop < 0 ? in.buf.length() : stop;
            if (stop == in.pos) {
                return null;
            }
            Matcher m = in.matcher(pattern, in.pos, stop);
            boolean ok = m.find();
            if (ok && m.requireEnd() && in.more(null)) {
                continue;
            }
            return ok ? m : null;
        }
    }

    /** SC6: a match in the next `horizon` chars (0: the rest of the input), or null. */
    Matcher findWithinHorizon(Pattern pattern, int horizon) {
        while (true) {
            int len = in.buf.length();
            boolean bounded = horizon > 0 && (long) in.pos + horizon <= len;
            int stop = bounded ? in.pos + horizon : len;
            Matcher m = in.matcher(pattern, in.pos, stop);
            boolean ok = m.find();
            if ((!bounded && m.hitEnd() || ok && m.requireEnd()) && in.more(bounded ? null : occurs(pattern, -1))) {
                continue;
            }
            return ok ? m : null;
        }
    }

    /** SC6: a match anchored at the position, or null. */
    Matcher skip(Pattern pattern) {
        while (true) {
            Matcher m = in.matcher(pattern, in.pos, in.buf.length());
            boolean ok = m.lookingAt();
            if (m.hitEnd() && in.more(runEnds(pattern))) {
                continue;
            }
            return ok ? m : null;
        }
    }
}
