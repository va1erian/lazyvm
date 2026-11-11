package lazy.ui;

/**
 * Anything the VM can send a message to: widgets, windows, dialogs, menus and timers. Each gets an
 * id when it is mounted: a registry slot plus a generation, so a message for a node that is gone
 * never reaches the node that took its slot (docs/ui.md, "Identity and lifetime").
 */
abstract class Node {
    /** The registry id, 0 while not mounted. */
    int id;

    Node() {}

    /** Handles a message: {@code kind} is one of {@link Ui}'s event kinds. */
    void fire(int kind, int i, double d, String s) {}

    final boolean mounted() {
        return id != 0;
    }

    /** Throws unless this node is mounted and the caller is the UI thread. */
    final void live() {
        if (!mounted()) throw new IllegalStateException("not shown in a window");
        Ui.check();
    }

    /** Runs {@code op} on the mounted node. */
    final Object call(int op, int i, double d, Object o) {
        live();
        return Ui.call0(id, op, i, d, o);
    }

    final Object call(int op, Object o) {
        return call(op, 0, 0, o);
    }

    final int callInt(int op) {
        return ((int[]) call(op, 0, 0, null))[0];
    }
}
