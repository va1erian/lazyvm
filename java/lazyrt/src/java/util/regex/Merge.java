package java.util.regex;

/** Builds the nodes that merge several parsed pieces: alternations of single chars and literal runs. */
final class Merge {
    private Merge() {
    }

    /** Alternatives that are each one char of a class are one class, which runs without recursion. */
    static Node alternation(Node[] branches) {
        CharClass[] parts = new CharClass[branches.length];
        int width = 1;
        for (int k = 0; k < branches.length; k++) {
            if (!(branches[k] instanceof Atoms.One)) {
                return new Node.Alt(branches);
            }
            parts[k] = ((Atoms.One) branches[k]).cc;
            width = Math.max(width, ((Atoms.One) branches[k]).width);
        }
        Atoms.One one = new Atoms.One(CharClass.cached(CharClass.anyOf(parts, parts.length)));
        one.fromAlternation = true;
        one.width = width;
        return one;
    }

    /** Builds a sequence, merging runs of plain literal chars into one string node. */
    static Node sequenceOf(Node[] items, int n) {
        // Observed on the oracle (RegexFolding): two or more case-folded literals in a row form a
        // literal string, which folds without the single character's exceptions (CharClass.FoldChar).
        for (int k = 1; k < n; k++) {
            if (isFoldedLiteral(items[k - 1]) && isFoldedLiteral(items[k])) {
                ((Atoms.One) items[k - 1]).cc.loosen();
                ((Atoms.One) items[k]).cc.loosen();
            }
        }
        Node[] out = new Node[n];
        int m = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            StringBuilder run = new StringBuilder();
            int reach = 0;
            int count = 0;
            while (j < n && items[j] instanceof Atoms.One && ((Atoms.One) items[j]).literal >= 0) {
                run.appendCodePoint(((Atoms.One) items[j]).literal);
                reach += ((Atoms.One) items[j]).width;
                count++;
                j++;
            }
            if (j - i >= 2) {
                out[m++] = new Atoms.Str(run.toString().toCharArray(), count, reach);
                i = j;
            } else {
                out[m++] = items[i++];
            }
        }
        if (m == 0) {
            return new Node.Empty();
        }
        if (m == 1) {
            return out[0];
        }
        Node[] exact = new Node[m];
        System.arraycopy(out, 0, exact, 0, m);
        return new Node.Seq(exact);
    }


    private static boolean isFoldedLiteral(Node n) {
        return n instanceof Atoms.One && ((Atoms.One) n).foldedLiteral;
    }
}
