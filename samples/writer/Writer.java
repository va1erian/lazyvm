import lazy.ui.Layout;
import lazy.ui.RichText;
import lazy.ui.StatusBar;
import lazy.ui.Toolbar;
import lazy.ui.Ui;
import lazy.ui.Window;

/**
 * LazyWriter on LazyVM: a word processor on xui's rich-text editor, ported from LazyOS's
 * {@code xui-app/crates/writer} (docs/ui.md, "Writer"). It keeps the original's window, toolbar,
 * formatting row, page setup, tables, files and shortcuts; printing waits for Messenger (S10).
 *
 * <pre>lazyvm-ui -jar writer.jar [--serif=FAMILY] [--mono=FAMILY] [file.lzw]</pre>
 *
 * {@code --serif} and {@code --mono} name the font families Serif and Mono stand for on this
 * system, a {@code +} for each space (LazyOS splits its launch line on spaces). As LazyOS's own
 * apps do, other {@code -} options ({@code --client}) and the {@code attempt=N} that LazyOS
 * appends are skipped, and the file must be an absolute path.
 */
public final class Writer {
    /** The paragraph kinds the picker offers, in RichText's order. */
    static final String[] BLOCKS = {"Normal", "Heading 1", "Heading 2", "Heading 3", "Quote"};
    static final String[] FAMILIES = {"Sans", "Serif", "Mono"};

    final Window window;
    final RichText editor = Ui.richText();
    final StatusBar status = Ui.statusBar("Untitled", "Saved", "0 words", "Page 1 of 1", "");
    final Tools tools;
    final Documents documents;
    final Pages pages;
    final Tables tables;
    /** The font families Serif and Mono stand for on this system. */
    final String serif, mono;

    /** The file the document came from or went to, or null. */
    String path;
    /** Whether the document changed since it was opened, saved or created. */
    boolean dirty;
    /** The last formatting under the selection, shown again after commands. */
    RichText.Style summary;

    Writer(int width, int height, String serif, String mono) {
        this.serif = serif;
        this.mono = mono;
        window = Ui.window("LazyWriter", width, height);
        tools = new Tools(this);
        documents = new Documents(this);
        pages = new Pages(this);
        tables = new Tables(this);
        editor.onChange(this::edited).onStyle(this::selection).onLink(url -> status.text(0, url));
        Layout content = Ui.column().add(commands().height(36), tools.row().fixed(34), editor.fill(1), status);
        window.content(content).onClose(() -> documents.guard(Documents.QUIT));
        shortcuts();
    }

    public static void main(String[] args) {
        String serif = "serif", mono = "monospace", file = null;
        for (String a : args) {
            if (a.startsWith("--serif=")) serif = a.substring(8).replace('+', ' ');
            else if (a.startsWith("--mono=")) mono = a.substring(7).replace('+', ' ');
            else if (file == null && !a.startsWith("-") && !a.startsWith("attempt=")
                && new java.io.File(a).isAbsolute()) file = a;
        }
        final Writer w = new Writer(960, 600, serif, mono);
        if (file != null) {
            final String path = file;
            Ui.executor().execute(() -> w.documents.openPath(path));
        }
        Ui.executor().execute(w::started);
        Ui.run(w.window);
    }

    /** Once the window is up: the title, the focus, and the evidence line LazyOS sessions grep. */
    private void started() {
        refreshTitle();
        editor.focus();
        System.out.println("WRITER:UP:PASS");
    }

    private Toolbar commands() {
        return Ui.toolbar()
            .item("FilePlus", "New (Ctrl+N)", "New", () -> documents.guard(Documents.NEW))
            .item("FolderOpen", "Open (Ctrl+O)", "Open", () -> documents.guard(Documents.OPEN))
            .item("Save", "Save (Ctrl+S)", "Save", documents::save)
            .item("Download", "Export as Markdown (Ctrl+E)", "Export", documents::export)
            .separator()
            .item("Undo2", "Undo (Ctrl+Z)", "Undo", () -> format(RichText.UNDO))
            .item("Redo2", "Redo (Ctrl+Y)", "Redo", () -> format(RichText.REDO))
            .separator()
            .item("Scissors", "Cut (Ctrl+X)", "Cut", () -> format(RichText.CUT))
            .item("Copy", "Copy (Ctrl+C)", "Copy", () -> format(RichText.COPY))
            .item("ClipboardPaste", "Paste (Ctrl+V)", "Paste", () -> format(RichText.PASTE))
            .separator()
            .item("Image", "Insert image", "Image", documents::insertImage)
            .item("Link", "Link the selection", "Link", documents.link::show)
            .item("SeparatorHorizontal", "Page break (Ctrl+Enter)", "Page break",
                () -> format(RichText.PAGE_BREAK));
    }

    /**
     * Ctrl+N, O, S, Shift+S, E and Q; the editor handles B, I, U, Z, Y, X, C, V and A. They wait
     * while a dialog is open.
     */
    private void shortcuts() {
        window.shortcut('n', Window.CTRL, () -> documents.guard(Documents.NEW));
        window.shortcut('o', Window.CTRL, () -> documents.guard(Documents.OPEN));
        window.shortcut('s', Window.CTRL, documents::save);
        window.shortcut('s', Window.CTRL | Window.SHIFT, documents::saveAs);
        window.shortcut('e', Window.CTRL, documents::export);
        window.shortcut('q', Window.CTRL, () -> documents.guard(Documents.QUIT));
    }

    /** The window title: {@code LazyWriter: <name>}, with {@code *} when modified. */
    static String title(String name, boolean dirty) {
        return "LazyWriter: " + (dirty ? "*" : "") + name;
    }

    static String wordsLabel(int words) {
        return words == 1 ? "1 word" : words + " words";
    }

    void refreshTitle() {
        String name = Names.displayName(path);
        window.title(title(name, dirty));
        status.text(0, name);
        status.text(1, dirty ? "Modified" : "Saved");
    }

    void refreshPages() {
        int[] p = editor.pageInfo();
        status.text(3, "Page " + p[0] + " of " + p[1]);
    }

    void refreshTable() {
        status.text(4, Tables.cellLabel(editor.tableCursor()));
    }

    /** The document changed: it now has {@code words} words. */
    private void edited(int words) {
        dirty = true;
        status.text(2, wordsLabel(words));
        refreshTitle();
        refreshPages();
        refreshTable();
    }

    /** The selection or its formatting changed: the formatting row follows it. */
    void selection(RichText.Style style) {
        tools.sync(style);
        summary = style;
        tools.syncWrap(editor.wrap());
        refreshPages();
        refreshTable();
    }

    /**
     * Runs an editor command from the toolbar and gives the text the focus back. The last
     * formatting is shown again: clicking a checked alignment unchecks its button without
     * changing the document, so no new formatting arrives to check it again.
     */
    void format(int command) {
        editor.exec(command);
        refocus();
    }

    void refocus() {
        editor.focus();
        if (summary != null) tools.sync(summary);
    }

    /** The link prompt closed: link the selection, unlink it when blank, or do nothing. */
    void linkChosen(String url) {
        if (url != null) editor.link(url);
        refocus();
    }
}
