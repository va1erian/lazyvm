package lazy.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A dialog, file picker or menu: it belongs to a window but not to its layout. It is made in the
 * window on its first {@code open} and lives until the window closes (docs/ui.md).
 */
abstract class Popup extends Node {
    final Window window;
    /** The stage the open dialog's {@code show} returned, completed when it closes. */
    private CompletableFuture<Object> answer;

    Popup(Window window) {
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
     * Shows a modal dialog and returns the stage of its answer, unless another dialog of its
     * window is open: then it shows nothing, so an app keeps no flag of its own, and the stage
     * is cancelled. The window's shortcuts wait while one is open.
     */
    @SuppressWarnings("unchecked")
    final <T> CompletionStage<T> showModal() {
        CompletableFuture<Object> stage = new CompletableFuture<Object>();
        if (window.modal != null) {
            // Cancelled, not failed: an app that reports failures need not tell this apart.
            stage.cancel(false);
        } else {
            open();
            window.modal = this;
            answer = stage;
        }
        return (CompletionStage<T>) (CompletionStage<?>) stage;
    }

    /**
     * The dialog closed, answered or cancelled: the window takes input again, {@code callback}
     * runs, then the stage completes with {@code value}. Either may show the next dialog. Does
     * nothing unless this is the window's open dialog: closing a closed one answers nothing.
     */
    final <T> void closed(Consumer<T> callback, T value) {
        if (window.modal != this) return;
        window.modal = null;
        CompletableFuture<Object> stage = answer;
        answer = null;
        try {
            if (callback != null) callback.accept(value);
        } finally {
            if (stage != null) stage.complete(value);
        }
    }

    /** Its window closed while it was open: the stage is cancelled. */
    final void abandon() {
        CompletableFuture<Object> stage = answer;
        answer = null;
        if (stage != null) stage.cancel(false);
    }
}
