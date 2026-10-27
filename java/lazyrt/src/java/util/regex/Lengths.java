package java.util.regex;

/**
 * Two length analyses whose results are visible, both fitted to behaviour
 * observed on the Temurin 8 oracle (RegexErrors for the first, RegexRegions
 * and the hitEnd tests for the second):
 *
 * <p>Whether a lookbehind body is accepted. Unbounded repeats are accepted when
 * nothing is added to the length around them and rejected otherwise: (?<=a*)
 * and (?<=\w+x) compile, (?<=ab*) and (?<=a.*b) do not, and a loop over a group
 * with branches or variable-width parts is rejected. The model that fits is
 * 32-bit length arithmetic with an unbounded repeat counting as
 * Integer.MAX_VALUE chars. (The matcher itself handles any width.)
 *
 * <p>The minimum match length that bounds where a failed search stops, which
 * decides hitEnd afterwards.
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
     * The minimum match length that fits the oracle's hitEnd results: counting
     * stops at a * + or {n,m} loop over a group whose body can vary, so such
     * patterns count as shorter than they are.
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
