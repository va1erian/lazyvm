package lazy.internal;

/**
 * The matcher behind {@link Regex}: a chain of nodes, each matching at an
 * index and then asking the rest of the chain, so backtracking is the Java
 * call stack. Single-char repeats loop instead of recursing, so `.*` over a
 * long input does not grow the stack.
 */
final class RegexNodes {
    private RegexNodes() {
    }

    /** One attempt: the input, the group bounds found so far, loop counters. */
    static final class State {
        final CharSequence in;
        final int len;
        final boolean whole;
        final int[] groups;
        final int[] count;
        final int[] start;
        int end;

        State(CharSequence in, int groupCount, int loops, boolean whole) {
            this.in = in;
            this.len = in.length();
            this.whole = whole;
            this.groups = new int[2 * (groupCount + 1)];
            this.count = new int[loops];
            this.start = new int[loops];
            java.util.Arrays.fill(groups, -1);
        }
    }

    abstract static class Node {
        Node next;

        abstract boolean match(State s, int i);
    }

    /** The end of the pattern. */
    static final class Accept extends Node {
        boolean match(State s, int i) {
            if (s.whole && i != s.len) {
                return false;
            }
            s.end = i;
            return true;
        }
    }

    /** Which chars a single-char node accepts. */
    abstract static class CharTest {
        abstract boolean test(char c);
    }

    static final class One extends Node {
        final CharTest test;

        One(CharTest test) {
            this.test = test;
        }

        boolean match(State s, int i) {
            return i < s.len && test.test(s.in.charAt(i)) && next.match(s, i + 1);
        }
    }

    /** `test` repeated min..max times, tried longest first unless lazy. */
    static final class Run extends Node {
        final CharTest test;
        final int min;
        final int max;
        final boolean greedy;

        Run(CharTest test, int min, int max, boolean greedy) {
            this.test = test;
            this.min = min;
            this.max = max;
            this.greedy = greedy;
        }

        boolean match(State s, int i) {
            int n = 0;
            while (n < max && i + n < s.len && test.test(s.in.charAt(i + n))) {
                n++;
            }
            if (n < min) {
                return false;
            }
            if (greedy) {
                for (int k = n; k >= min; k--) {
                    if (next.match(s, i + k)) {
                        return true;
                    }
                }
                return false;
            }
            for (int k = min; k <= n; k++) {
                if (next.match(s, i + k)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Alternatives, each of whose tails already leads to this node's next. */
    static final class Alt extends Node {
        final Node[] branches;

        Alt(Node[] branches) {
            this.branches = branches;
        }

        boolean match(State s, int i) {
            for (Node b : branches) {
                if (b.match(s, i)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Records where group `n` starts or ends, undoing it on failure. */
    static final class Mark extends Node {
        final int slot;

        Mark(int slot) {
            this.slot = slot;
        }

        boolean match(State s, int i) {
            int old = s.groups[slot];
            s.groups[slot] = i;
            if (next.match(s, i)) {
                return true;
            }
            s.groups[slot] = old;
            return false;
        }
    }

    /** A repeated sub-pattern; the body's tail is a {@link Again} back to it. */
    static final class Loop extends Node {
        Node body;
        final int min;
        final int max;
        final boolean greedy;
        final int id;

        Loop(int min, int max, boolean greedy, int id) {
            this.min = min;
            this.max = max;
            this.greedy = greedy;
            this.id = id;
        }

        boolean match(State s, int i) {
            int c = s.count[id];
            int st = s.start[id];
            s.count[id] = 0;
            s.start[id] = -1;
            boolean ok = step(s, i);
            s.count[id] = c;
            s.start[id] = st;
            return ok;
        }

        /** After `count` iterations: another one, or the rest; an empty iteration ends the loop. */
        boolean step(State s, int i) {
            int c = s.count[id];
            if (c < min) {
                return enter(s, i);
            }
            boolean more = c < max && i != s.start[id];
            if (greedy) {
                return (more && enter(s, i)) || next.match(s, i);
            }
            return next.match(s, i) || (more && enter(s, i));
        }

        private boolean enter(State s, int i) {
            int c = s.count[id];
            int st = s.start[id];
            s.count[id] = c + 1;
            s.start[id] = i;
            if (body.match(s, i)) {
                return true;
            }
            s.count[id] = c;
            s.start[id] = st;
            return false;
        }
    }

    static final class Again extends Node {
        final Loop loop;

        Again(Loop loop) {
            this.loop = loop;
        }

        boolean match(State s, int i) {
            return loop.step(s, i);
        }
    }

    /** `^`, `$`, `\b` and `\B` without the MULTILINE flag. */
    static final class Anchor extends Node {
        final char kind;

        Anchor(char kind) {
            this.kind = kind;
        }

        boolean match(State s, int i) {
            boolean ok;
            switch (kind) {
                case '^':
                    ok = i == 0;
                    break;
                case '$':
                    ok = i == s.len || atFinalTerminator(s, i);
                    break;
                default:
                    boolean edge = word(s, i - 1) != word(s, i);
                    ok = kind == 'b' ? edge : !edge;
            }
            return ok && next.match(s, i);
        }

        private static boolean word(State s, int i) {
            if (i < 0 || i >= s.len) {
                return false;
            }
            char c = s.in.charAt(i);
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /** `$` also matches before a line terminator that ends the input. */
        private static boolean atFinalTerminator(State s, int i) {
            char c = s.in.charAt(i);
            if (c == '\r' && i + 2 == s.len && s.in.charAt(i + 1) == '\n') {
                return true;
            }
            return i + 1 == s.len && Regex.isLineTerminator(c);
        }
    }
}
