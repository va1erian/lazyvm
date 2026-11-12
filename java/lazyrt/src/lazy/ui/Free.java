package lazy.ui;

/**
 * A dialog, file picker or menu: it belongs to a window but not to its layout. It is made in the
 * window on its first {@code open} and lives until the window closes (docs/ui.md).
 */
abstract class Free extends Node {
    final Window window;

    Free(Window window) {
        this.window = window;
    }

    /** Appends {@code kind id} and this node's fields. */
    abstract void encode(Encoder out);

    /** Makes the node in its window, the first time. */
    final void make() {
        Ui.check();
        if (mounted()) return;
        if (!window.mounted()) throw new IllegalStateException("its window is not open");
        Encoder out = new Encoder();
        encode(out);
        try {
            Ui.make0(window.id, out.ops(), out.objects());
        } catch (RuntimeException e) {
            out.abandon();
            throw e;
        }
        window.owned.add(this);
        made();
    }

    /** Runs once the node is made, to apply what was set before. */
    void made() {}

    /** Throws if the node is made: its options are fixed from then on. */
    final void fixed() {
        if (mounted()) throw new IllegalStateException("set this before the first open");
    }

    final void open() {
        make();
        call(Op.OPEN, null);
    }

    /**
     * Shows a modal dialog, unless another dialog of its window is open: then it does nothing,
     * so an app keeps no flag of its own. The window's shortcuts wait while one is open.
     */
    final void showModal() {
        if (window.modal != null) return;
        open();
        window.modal = this;
    }

    /** The dialog closed, answered or cancelled. */
    final void closed() {
        if (window.modal == this) window.modal = null;
    }
}
