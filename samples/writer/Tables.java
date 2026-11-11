import lazy.ui.Menu;
import lazy.ui.RichText;

/**
 * The Table menu: an Insert table submenu of sizes, the row, column and table commands, and the
 * Header row and Borders switches, enabled while the caret is in a table.
 */
final class Tables {
    private static final int[] SIZES = {2, 3, 4, 5};
    // Menu entries, in the order they are added.
    private static final int INSERT_MENU = 0, ROW_ABOVE = 6, HEADER = 15, BORDERS = 16;
    private static final int[] COMMANDS = {6, 7, 8, 9, 11, 12, 13, 15, 16};

    private final Writer app;
    private final Menu menu;

    Tables(final Writer app) {
        this.app = app;
        menu = app.window.menu()
            .submenu("Insert table", "Table", m -> {
                for (final int n : SIZES) m.item(n + " x " + n, () -> run(() -> app.editor.insertTable(n, n)));
            })
            .separator()
            .item("Insert row above", "BetweenHorizontalStart", () -> exec(RichText.ROW_ABOVE))
            .item("Insert row below", "BetweenHorizontalEnd", () -> exec(RichText.ROW_BELOW))
            .item("Insert column left", "BetweenVerticalStart", () -> exec(RichText.COLUMN_LEFT))
            .item("Insert column right", "BetweenVerticalEnd", () -> exec(RichText.COLUMN_RIGHT))
            .separator()
            .item("Delete rows", () -> exec(RichText.DELETE_ROWS))
            .item("Delete columns", () -> run(app.editor::deleteColumns))
            .item("Delete table", "Trash2", () -> run(app.editor::deleteTable))
            .separator()
            .check("Header row", false, on -> run(() -> app.editor.tableHeader(on)))
            .check("Borders", false, on -> run(() -> app.editor.tableBorders(on)));
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
        int[] cursor = app.editor.tableCursor();
        menu.enabled(INSERT_MENU, cursor == null);
        for (int entry : COMMANDS) menu.enabled(entry, cursor != null);
        menu.checked(HEADER, cursor != null && cursor[4] != 0);
        menu.checked(BORDERS, cursor != null && cursor[5] != 0);
        menu.show(app.tools.table);
    }

    /** The status bar's table part: {@code Table: row 2, column 3}, or empty outside a table. */
    static String cellLabel(int[] cursor) {
        return cursor == null ? "" : "Table: row " + (cursor[0] + 1) + ", column " + (cursor[1] + 1);
    }
}
