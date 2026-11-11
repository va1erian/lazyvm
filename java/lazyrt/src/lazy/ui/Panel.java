package lazy.ui;

/**
 * A container whose content can be replaced at any time: the way to rebuild a part of a window
 * (docs/ui.md, "The model"). The widgets of the old content leave the screen for good; a message
 * still queued for one of them is dropped.
 */
public final class Panel extends Widget {
    private Layout content;
    private boolean plain;

    Panel(Layout content) {
        this.content = Ui.nonNull(content, "content");
    }

    /** Draws no frame or background of its own, before the window opens. */
    public Panel plain() {
        if (mounted()) throw new IllegalStateException("set plain before the window opens");
        plain = true;
        return this;
    }

    /** The content shown. */
    public Layout content() {
        return content;
    }

    /**
     * Shows {@code layout} instead of the current content. The old content's widgets leave the
     * screen first, and their slots go to the new ones; should the VM refuse the new content, the
     * panel is left empty of live widgets.
     */
    public Panel set(Layout layout) {
        Ui.nonNull(layout, "layout");
        if (!mounted()) {
            content = layout;
            return this;
        }
        live();
        content.unmount();
        content = layout;
        Encoder out = new Encoder();
        Ui.building = window;
        try {
            layout.encode(out);
            Ui.mount0(id, out.ops(), out.objects());
        } catch (RuntimeException e) {
            out.abandon();
            throw e;
        } finally {
            Ui.building = null;
        }
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.PANEL);
        out.flag(plain);
        content.encode(out);
    }

    @Override
    void unmount() {
        // Children first: freed slots are reused last-in first-out, so a new tree
        // encoded in the same order takes the same slots.
        content.unmount();
        super.unmount();
    }
}
