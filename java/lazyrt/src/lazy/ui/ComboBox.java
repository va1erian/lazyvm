package lazy.ui;

import java.util.function.IntConsumer;

/** A drop-down list of fixed items, one selected. */
public final class ComboBox extends Widget {
    private final String[] items;
    private final String[] icons;
    private int selected;
    private IntConsumer onSelect;

    ComboBox(String[] items) {
        if (items.length == 0) throw new IllegalArgumentException("a combo box needs items");
        this.items = items.clone();
        for (String s : this.items) Ui.nonNull(s, "item");
        this.icons = new String[items.length];
    }

    /** Shows the Lucide icon {@code name} beside item {@code index}, before the window opens. */
    public ComboBox icon(int index, String name) {
        if (mounted()) throw new IllegalStateException("set icons before the window opens");
        icons[check(index)] = Ui.nonNull(name, "name");
        return this;
    }

    public int count() {
        return items.length;
    }

    /**
     * The selected item's index. The user changes it, so it is read from the window, unless
     * {@link #onSelect} keeps this side's copy current.
     */
    public int selected() {
        return mounted() && onSelect == null ? callInt(Op.GET_SELECTED) : selected;
    }

    /** Selects item {@code index}, without running the select action. */
    public ComboBox select(int index) {
        check(index);
        if (mounted() && (onSelect == null || index != selected)) call(Op.SELECT, index, 0, null);
        selected = index;
        return this;
    }

    private int check(int index) {
        if (index < 0 || index >= items.length) throw new IndexOutOfBoundsException("item " + index);
        return index;
    }

    /** Runs {@code action} with the index the user picks. */
    public ComboBox onSelect(IntConsumer action) {
        if (mounted()) throw new IllegalStateException("set onSelect before the window opens");
        onSelect = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.COMBO);
        out.op(items.length);
        for (int i = 0; i < items.length; i++) {
            out.string(items[i]);
            out.string(icons[i]);
        }
        out.op(selected);
        out.flag(onSelect != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.SELECT && onSelect != null) {
            selected = i;
            onSelect.accept(i);
        }
    }
}
