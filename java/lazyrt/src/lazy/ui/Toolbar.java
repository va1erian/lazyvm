package lazy.ui;

import java.util.ArrayList;

/**
 * A row of icon buttons, with optional captions, and separators between groups. Icons are Lucide
 * names as xui spells them ({@code "FilePlus"}).
 */
public final class Toolbar extends Widget {
    private final ArrayList<String[]> items = new ArrayList<String[]>();
    private final ArrayList<Runnable> actions = new ArrayList<Runnable>();

    Toolbar() {}

    /** Adds a button showing {@code icon}, named {@code tooltip} on hover, running {@code action}. */
    public Toolbar item(String icon, String tooltip, Runnable action) {
        return item(icon, tooltip, null, action);
    }

    /** Adds a button showing {@code icon} and the caption {@code text}. */
    public Toolbar item(String icon, String tooltip, String text, Runnable action) {
        fixed();
        items.add(new String[] {Ui.nonNull(icon, "icon"), Ui.nonNull(tooltip, "tooltip"), text});
        actions.add(Ui.nonNull(action, "action"));
        return this;
    }

    /** Adds a line between groups of buttons. */
    public Toolbar separator() {
        fixed();
        items.add(new String[] {null, "", null});
        return this;
    }

    private void fixed() {
        if (mounted()) throw new IllegalStateException("a toolbar is fixed once its window opens");
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.TOOLBAR);
        out.op(items.size());
        for (String[] item : items) {
            out.string(item[0]);
            out.string(item[1]);
            out.string(item[2]);
        }
        out.flag(!actions.isEmpty());
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.CLICK && i >= 0 && i < actions.size()) actions.get(i).run();
    }
}
