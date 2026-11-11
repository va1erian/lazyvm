package lazy.ui;

import java.util.ArrayList;
import java.util.function.IntConsumer;

/**
 * A list of rows, with optional columns. Rows are pushed whole ({@link #rows}); the list paints
 * only what is visible, and scrolling it runs no bytecode (docs/ui.md, "Lists").
 */
public final class ListView extends Widget {
    private final ArrayList<String> titles = new ArrayList<String>();
    private final ArrayList<Integer> widths = new ArrayList<Integer>();
    private String[] cells = new String[0];
    private int selected = -1;
    private IntConsumer onSelect, onActivate;

    ListView() {}

    /**
     * Adds a column titled {@code title}, {@code width} design units wide, or sharing what is
     * left when {@code width} is 0. Before the window opens.
     */
    public ListView column(String title, int width) {
        if (mounted()) throw new IllegalStateException("add columns before the window opens");
        titles.add(Ui.nonNull(title, "title"));
        widths.add(length(width));
        return this;
    }

    private int columns() {
        return Math.max(1, titles.size());
    }

    /** The number of rows. */
    public int count() {
        return cells.length / columns();
    }

    /**
     * Replaces the rows: {@code cells} holds them one after another, a cell per column (one cell
     * per row with no columns). The selection is cleared.
     */
    public ListView rows(String... cells) {
        if (cells.length % columns() != 0)
            throw new IllegalArgumentException(cells.length + " cells do not fill rows of " + columns());
        String[] copy = cells.clone();
        for (String s : copy) Ui.nonNull(s, "cell");
        if (mounted()) call(Op.ROWS, columns(), 0, copy);
        this.cells = copy;
        selected = -1;
        return this;
    }

    /** The cell at {@code row} and {@code column}. */
    public String cell(int row, int column) {
        if (row < 0 || row >= count() || column < 0 || column >= columns())
            throw new IndexOutOfBoundsException(row + ", " + column);
        return cells[row * columns() + column];
    }

    /** The selected row, or -1; the user changes it, so it is read from the window. */
    public int selected() {
        return mounted() ? callInt(Op.GET_SELECTED) : selected;
    }

    /** Selects {@code row}, or nothing with -1, without running the select action. */
    public ListView select(int row) {
        if (row < -1 || row >= count()) throw new IndexOutOfBoundsException("row " + row);
        if (mounted()) call(Op.SELECT, row, 0, null);
        selected = row;
        return this;
    }

    /** Runs {@code action} with the row the user selects. */
    public ListView onSelect(IntConsumer action) {
        if (mounted()) throw new IllegalStateException("set onSelect before the window opens");
        onSelect = Ui.nonNull(action, "action");
        return this;
    }

    /** Runs {@code action} with the row the user opens (double click or Enter). */
    public ListView onActivate(IntConsumer action) {
        if (mounted()) throw new IllegalStateException("set onActivate before the window opens");
        onActivate = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.LIST);
        out.op(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            out.string(titles.get(i));
            out.op(widths.get(i));
        }
        out.strings(cells);
        out.op(selected);
        out.flag(onSelect != null);
        out.flag(onActivate != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.SELECT && onSelect != null) onSelect.accept(i);
        else if (kind == Ui.ACTIVATE && onActivate != null) onActivate.accept(i);
    }
}
