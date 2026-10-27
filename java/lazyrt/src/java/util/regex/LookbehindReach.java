package java.util.regex;

/**
 * How far back a lookbehind body may look, and whether the body is accepted at
 * all. The rules were fitted to probes of the Temurin 8 oracle, tabulated in
 * docs/regex.md ("Lookbehind reach") and pinned by RegexLookbehind:
 *
 * <ul>
 * <li>Each piece of the body has a reach, counted in characters as written (a character
 * above U+FFFF written as a pair or as \x{..} is one): a class or a character 1, a literal
 * run its length, a class repeat n times n, an unbounded repeat 2147483647.
 * <li>Reaches add up along the body with ordinary 32-bit int overflow, no
 * clamping. An alternation contributes its longest branch, counted from -1.
 * <li>A repeat written * + {n} {n,m} or {n,} is rejected when its own reach
 * overflows the total so far (a+b+ and ab* are rejected, a+b and a*a are not).
 * A '?' on a char or class never is, so a+_? and a*[ab]?+ are accepted; a ?+
 * on a group is checked like the others. A '?' of any kind on another atom (an
 * atomic group, \R) counts the atom as if it were not repeated: a(?>b+)? is
 * rejected, x*\S(?>b+)? and a+\R? are not.
 * <li>The total starts again from zero after an alternation and after a group
 * repeated by a plain or lazy '?': (?:a|b)a+ and (?:ab)?a+ are accepted while
 * (?:ab)?+a+ and ab?a+ are not. The restart covers everything that follows in the
 * same branch or repeated group, however many groups close in between, except
 * that it ends where an atomic group around it closes.
 * <li>A repeat of a group is rejected when the group has alternatives or any
 * variable part, even with a small count: (?:a|b){2} and (?:a|b)+ fail but
 * (?:a|b)? and (?>a|b)+ are accepted. A lookaround inside the group counts as
 * fixed and zero wide.
 * <li>A back reference is rejected.
 * <li>The matcher looks back from the shortest possible body to the reach, in
 * int arithmetic. In char mode a reach that wrapped to a small negative number
 * therefore finds nothing, as on the oracle; in code-point mode the floor is
 * walked forward instead (GroupNodes.Look.behindByCodePoints, docs/regex.md).
 * </ul>
 */
final class LookbehindReach {
    /** The reach in int arithmetic; meaningless when {@link #valid} is false. */
    final int max;
    /** False when the oracle rejects the body as having no maximum length. */
    final boolean valid;

    // The total of the current stretch, and of the stretches before the last restart.
    private int sum;
    private int carried;
    private boolean ok = true;

    private LookbehindReach(Node body) {
        study(body);
        this.max = carried + sum;
        this.valid = ok;
    }

    private void restart() {
        carried += sum;
        sum = 0;
    }

    static LookbehindReach of(Node body) {
        return new LookbehindReach(body);
    }

    private void study(Node n) {
        if (n instanceof Atoms.One) {
            sum++;
            if (((Atoms.One) n).fromAlternation) {
                restart();
            }
        } else if (n instanceof Atoms.Str) {
            sum += ((Atoms.Str) n).min();
        } else if (n instanceof Atoms.LineBreak) {
            sum += 2;
        } else if (n instanceof Atoms.BackRef) {
            ok = false;
        } else if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                study(item);
            }
        } else if (n instanceof Node.Alt) {
            alternatives(((Node.Alt) n).branches);
        } else if (n instanceof GroupNodes.Group) {
            study(((GroupNodes.Group) n).body());
        } else if (n instanceof GroupNodes.Atomic) {
            // Only a written (?>..) is a stretch of its own. The wrapper of a possessive repeat
            // gives the same result studied inline, and inline costs fewer frames per level.
            GroupNodes.Atomic a = (GroupNodes.Atomic) n;
            if (a.written()) {
                atomic(a.body());
            } else {
                study(a.body());
            }
        } else if (n instanceof Repeat.CharRepeat) {
            charRepeat((Repeat.CharRepeat) n);
        } else if (n instanceof Repeat.Loop) {
            loop((Repeat.Loop) n);
        }
    }

    private void charRepeat(Repeat.CharRepeat r) {
        if (r.question) {
            sum++;
            if (r.grouped && r.mode != Repeat.POSSESSIVE) {
                restart();
            }
        } else if (r.fromAlternation && r.mode != Repeat.POSSESSIVE) {
            ok = false;
        } else {
            repeat(1, true, r.maxCount());
        }
    }

    private void loop(Repeat.Loop loop) {
        if (loop.question && !loop.inGroup) {
            // Observed on the oracle (RegexLookbehindEdges, atomicStretch): '?', '??' and '?+' on
            // an atom that is not a (..) or (?:..) group, such as an atomic group or \R, count it
            // as if it were not repeated.
            study(loop.body());
            return;
        }
        LookbehindReach inner = new LookbehindReach(loop.body());
        if (loop.question && loop.possessive && fixedShape(loop.body(), true)) {
            sum += inner.max;
        } else if (loop.question && loop.possessive) {
            repeat(inner.max, inner.valid, 1);
        } else if (loop.question) {
            sum += inner.max;
            ok &= inner.valid;
            restart();
        } else if (loop.grouped && !fixedShape(loop.body(), true)) {
            ok = false;
        } else {
            repeat(inner.max, inner.valid, loop.maxCount());
        }
    }

    /**
     * Observed on the oracle (RegexLookbehindEdges, atomicStretch): an atomic group is a stretch
     * of its own that starts from the running total, so the repeats in it are checked against
     * what came before, and a restart inside it ends where it closes: (?>(?:b|c)d+)b+ is
     * accepted, (?:(?:b|c)d+)(?>b+)? is not.
     */
    private void atomic(Node body) {
        // Studied in place, not through a nested instance, so a nesting level costs no more
        // frames than before this rule (the depth reached before a stack overflow, docs/regex.md).
        int outer = carried;
        carried = 0;
        study(body);
        sum += carried;
        carried = outer;
    }

    private void alternatives(Node[] branches) {
        int longest = -1;
        for (Node b : branches) {
            LookbehindReach inner = new LookbehindReach(b);
            longest = Math.max(longest, inner.max);
            ok &= inner.valid;
        }
        sum += longest;
        restart();
    }

    private void repeat(int bodyReach, boolean bodyOk, int count) {
        int before = sum;
        sum = bodyReach * count + before;
        if (!bodyOk || sum < before) {
            ok = false;
        }
    }

    /**
     * True when a group body has no alternatives or variable repeats, and, for a lookbehind, no
     * back references; otherwise (the shortest-match rule in Repeat) back references are fixed
     * and an atomic group hides what it holds.
     * Observed on the oracle: a lookaround inside the group takes no width and does not make it
     * variable, so (?<=(?:(?=b)b){2})c is accepted. Pinned by RegexLookbehind, blessed in
     * RegexLookbehind.out; mutation: count a lookaround as variable again.
     */
    static boolean fixedShape(Node n, boolean forLookbehind) {
        if (n instanceof Node.Alt) {
            return false;
        }
        if (n instanceof Atoms.BackRef) {
            return !forLookbehind;
        }
        if (n instanceof Atoms.One) {
            return !((Atoms.One) n).fromAlternation;
        }
        if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                if (!fixedShape(item, forLookbehind)) {
                    return false;
                }
            }
            return true;
        }
        if (n instanceof GroupNodes.Group) {
            return fixedShape(((GroupNodes.Group) n).body(), forLookbehind);
        }
        if (n instanceof GroupNodes.Atomic) {
            return !forLookbehind || fixedShape(((GroupNodes.Atomic) n).body(), true);
        }
        if (n instanceof Repeat.CharRepeat) {
            Repeat.CharRepeat r = (Repeat.CharRepeat) n;
            return !r.fromAlternation && r.minCount() == r.maxCount();
        }
        if (n instanceof Repeat.Loop) {
            Repeat.Loop l = (Repeat.Loop) n;
            return l.minCount() == l.maxCount() && fixedShape(l.body(), forLookbehind);
        }
        return true;
    }
}
