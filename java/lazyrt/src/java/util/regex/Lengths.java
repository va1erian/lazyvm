package java.util.regex;

/**
 * Static analysis of a node tree that mimics the reference implementation's,
 * because both results are observable: whether a lookbehind body "has a
 * maximum length" (a compile error otherwise), and the minimum match length
 * that bounds where a search tries to start (it decides hitEnd after a failed
 * find).
 *
 * <p>The maximum-length rule: Its length arithmetic is 32-bit: an unbounded
 * repeat counts as Integer.MAX_VALUE chars and is accepted unless adding it to
 * the length before it overflows. So (?<=a*) and (?<=\w+x) are accepted and
 * (?<=ab*) is rejected; a loop over a group with branches or variable-width
 * parts is always rejected. (The matcher itself handles any width.)
 */
final class Lengths {
    private int max;
    private boolean valid = true;
    private int min;
    private boolean stopped;

    private Lengths() {
    }

    static boolean hasMaxLength(Node body) {
        Lengths s = new Lengths();
        s.study(body);
        return s.valid;
    }

    /**
     * The minimum match length as the reference implementation computes it: it
     * stops adding at a loop over a body that can vary, so patterns with such
     * a loop count as having a shorter minimum than they really have.
     */
    static int minLength(Node root) {
        Lengths s = new Lengths();
        s.walkMin(root);
        return s.min;
    }

    private void walkMin(Node n) {
        if (stopped) {
            return;
        }
        if (n instanceof Atoms.One || n instanceof Atoms.LineBreak) {
            min = Node.add(min, 1);
        } else if (n instanceof Atoms.Str) {
            min = Node.add(min, ((Atoms.Str) n).chars.length);
        } else if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                walkMin(item);
            }
        } else if (n instanceof Node.Alt) {
            int shortest = Node.UNBOUNDED;
            for (Node b : ((Node.Alt) n).branches) {
                Lengths inner = new Lengths();
                inner.walkMin(b);
                shortest = Math.min(shortest, inner.min);
            }
            min = Node.add(min, shortest);
        } else if (n instanceof GroupNodes.Group) {
            walkMin(((GroupNodes.Group) n).body());
        } else if (n instanceof GroupNodes.Atomic) {
            // A loop inside an atomic group adds nothing but does not end the analysis.
            Lengths inner = new Lengths();
            inner.walkMin(((GroupNodes.Atomic) n).body());
            min = Node.add(min, inner.min);
        } else if (n instanceof Repeat.CharRepeat) {
            min = Node.add(min, ((Repeat.CharRepeat) n).minCount());
        } else if (n instanceof Repeat.Loop) {
            Repeat.Loop loop = (Repeat.Loop) n;
            if (!loop.grouped || fixedShape(loop.body())) {
                Lengths inner = new Lengths();
                inner.walkMin(loop.body());
                min = Node.add(min, Node.mul(inner.min, loop.minCount()));
            } else {
                stopped = true;
            }
        }
    }

    private void study(Node n) {
        if (n instanceof Atoms.One) {
            max++;
        } else if (n instanceof Atoms.Str) {
            max += ((Atoms.Str) n).chars.length;
        } else if (n instanceof Atoms.LineBreak) {
            max += 2;
        } else if (n instanceof Atoms.BackRef) {
            valid = false;
        } else if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                study(item);
            }
        } else if (n instanceof Node.Alt) {
            alternatives(((Node.Alt) n).branches);
        } else if (n instanceof GroupNodes.Group) {
            study(((GroupNodes.Group) n).body());
        } else if (n instanceof GroupNodes.Atomic) {
            study(((GroupNodes.Atomic) n).body());
        } else if (n instanceof Repeat.CharRepeat) {
            repeat(1, true, ((Repeat.CharRepeat) n).maxCount());
        } else if (n instanceof Repeat.Loop) {
            Repeat.Loop loop = (Repeat.Loop) n;
            Lengths inner = new Lengths();
            inner.study(loop.body());
            repeat(inner.max, inner.valid && (!loop.grouped || fixedShape(loop.body())), loop.maxCount());
        }
    }

    private void alternatives(Node[] branches) {
        int longest = -1;
        for (Node b : branches) {
            Lengths inner = new Lengths();
            inner.study(b);
            longest = Math.max(longest, inner.max);
            valid &= inner.valid;
        }
        max += longest;
    }

    private void repeat(int bodyMax, boolean bodyValid, int count) {
        if (valid && bodyValid) {
            int before = max;
            max = bodyMax * count + before;
            if (max < before) {
                valid = false;
            }
        } else {
            valid = false;
        }
    }

    /** True when a loop body has no branches, lookarounds or variable-width repeats. */
    private static boolean fixedShape(Node n) {
        if (n instanceof Node.Alt || n instanceof GroupNodes.Look || n instanceof Atoms.BackRef) {
            return false;
        }
        if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                if (!fixedShape(item)) {
                    return false;
                }
            }
            return true;
        }
        if (n instanceof GroupNodes.Group) {
            return fixedShape(((GroupNodes.Group) n).body());
        }
        if (n instanceof GroupNodes.Atomic) {
            return fixedShape(((GroupNodes.Atomic) n).body());
        }
        if (n instanceof Repeat.CharRepeat) {
            return ((Repeat.CharRepeat) n).minCount() == ((Repeat.CharRepeat) n).maxCount();
        }
        if (n instanceof Repeat.Loop) {
            Repeat.Loop l = (Repeat.Loop) n;
            return l.minCount() == l.maxCount() && fixedShape(l.body());
        }
        return true;
    }
}
