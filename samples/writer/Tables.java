import lazy.ui.Menu;
import lazy.ui.RichText;

/**
 * The Table menu: an Insert table submenu of sizes, the row, column and table commands, and the
 * Header row and Borders switches, enabled while the caret is in a table.
 */
final class Tables {
    private static final int[] SIZES = {2, 3, 4, 5};

    private final Writer app;
    private final Menu menu;
    /** Where the caret was in a table as the menu opened, or null outside one. */
    private int[] cursor;

    Tables(final Writer app) {
        this.app = app;
        menu = app.window.menu()
            .enabledWhen(() -> cursor == null, m -> m.submenu("Insert table", "Table", sizes -> {
                for (final int n : SIZES) sizes.item(n + " x " + n, () -> run(() -> app.editor.insertTable(n, n)));
            }))
            .separator()
            .enabledWhen(() -> cursor != null, m -> m
                .item("Insert row above", "BetweenHorizontalStart", () -> exec(RichText.ROW_ABOVE))
                .item("Insert row below", "BetweenHorizontalEnd", () -> exec(RichText.ROW_BELOW))
                .item("Insert column left", "BetweenVerticalStart", () -> exec(RichText.COLUMN_LEFT))
                .item("Insert column right", "BetweenVerticalEnd", () -> exec(RichText.COLUMN_RIGHT))
                .separator()
                .item("Delete rows", () -> exec(RichText.DELETE_ROWS))
                .item("Delete columns", () -> run(app.editor::deleteColumns))
                .item("Delete table", "Trash2", () -> run(app.editor::deleteTable))
                .separator()
                .check("Header row", () -> flag(4), on -> run(() -> app.editor.tableHeader(on)))
                .check("Borders", () -> flag(5), on -> run(() -> app.editor.tableBorders(on))));
    }

    /** The caret's table's header (4) or borders (5) switch. */
    private boolean flag(int i) {
        return cursor != null && cursor[i] != 0;
    }

    private void exec(int command) {
        run(() -> app.editor.exec(command));
    }

    /** Runs a table command as one undoable step, then shows where the caret is. */
    private void run(Runnable command) {
        command.run();
        app.refocus();
        app.refreshTable();
    }

    /** Shows the menu under its button, its commands enabled for where the caret is. */
    void show() {
        cursor = app.editor.tableCursor();
        menu.show(app.tools.table);
    }

    /** The status bar's table part: {@code Table: row 2, column 3}, or empty outside a table. */
    static String cellLabel(int[] cursor) {
        return cursor == null ? "" : "Table: row " + (cursor[0] + 1) + ", column " + (cursor[1] + 1);
    }
}
