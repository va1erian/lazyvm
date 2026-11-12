package lazy.ui;

import java.util.ArrayList;

/**
 * Builds the flat op list widgets are handed to the VM as: ints, plus a table of the strings and
 * int arrays they index (docs/ui.md, "Building"). The order of each node's fields is the contract
 * {@code crates/xui-bind} decodes.
 */
final class Encoder {
    // Layouts, widgets and containers (crates/xui-bind/src/build.rs).
    static final int COLUMN = 1, ROW = 2, WRAP = 3;
    static final int LABEL = 10, BUTTON = 11, TOGGLE = 12, CHECK = 13, EDIT = 14, TEXT_AREA = 15;
    static final int COMBO = 16, LIST = 17, SLIDER = 18, PROGRESS = 19, STATUS = 20, TOOLBAR = 21;
    static final int SEPARATOR = 22, SPACER = 23;
    static final int PANEL = 30, SCROLL = 31, TABS = 32, GROUP = 33, CANVAS = 40, RICH = 50;
    // Dialogs and menus (popup.rs).
    static final int DIALOG = 60, TASK = 61, FILE = 62, MENU = 63;

    private int[] ops = new int[64];
    private int size;
    private final ArrayList<Object> objects = new ArrayList<Object>();
    /** The nodes this list mounts, so a failed mount can give their ids back. */
    final ArrayList<Node> nodes = new ArrayList<Node>();

    void op(int v) {
        if (size == ops.length) {
            int[] grown = new int[size * 2];
            System.arraycopy(ops, 0, grown, 0, size);
            ops = grown;
        }
        ops[size++] = v;
    }

    void flag(boolean v) {
        op(v ? 1 : 0);
    }

    /** Starts a node: its opcode and the id it gets in the registry. */
    void node(int op, Node n) {
        if (n.mounted()) throw new IllegalStateException("already shown in a window");
        Ui.register(n);
        nodes.add(n);
        op(op);
        op(n.id);
    }

    /** A string, or -1 for none. */
    void string(String s) {
        if (s == null) {
            op(-1);
            return;
        }
        op(objects.size());
        objects.add(s);
    }

    void strings(String[] list) {
        op(list.length);
        for (String s : list) string(Ui.nonNull(s, "string"));
    }

    void ints(int[] values) {
        op(objects.size());
        objects.add(values);
    }

    int[] ops() {
        int[] out = new int[size];
        System.arraycopy(ops, 0, out, 0, size);
        return out;
    }

    Object[] objects() {
        return objects.toArray();
    }

    /** Gives back the ids of everything encoded, after the VM refused the list. */
    void abandon() {
        for (Node n : nodes) Ui.free(n);
    }
}
