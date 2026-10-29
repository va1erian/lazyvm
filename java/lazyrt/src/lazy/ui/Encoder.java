package lazy.ui;

import java.util.ArrayList;

/** Builds the flat op list a window is handed to the VM as (docs/ui.md, "Building"). */
final class Encoder {
    static final int COLUMN = 1, ROW = 2, LABEL = 3, BUTTON = 4;

    private int[] ops = new int[64];
    private int size;
    final ArrayList<String> strings = new ArrayList<String>();

    void op(int v) {
        if (size == ops.length) {
            int[] grown = new int[size * 2];
            System.arraycopy(ops, 0, grown, 0, size);
            ops = grown;
        }
        ops[size++] = v;
    }

    /** Starts a node: its opcode and the id it gets in the registry. */
    void node(int op, Widget w) {
        if (w.mounted()) throw new IllegalStateException("widget is already in a window");
        w.id = Ui.register(w);
        op(op);
        op(w.id);
    }

    void string(String s) {
        op(strings.size());
        strings.add(s);
    }

    int[] ops() {
        int[] out = new int[size];
        System.arraycopy(ops, 0, out, 0, size);
        return out;
    }
}
