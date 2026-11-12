import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lazy.ui.Button;
import lazy.ui.Edit;
import lazy.ui.Label;
import lazy.ui.ListView;
import lazy.ui.Ui;
import lazy.ui.Window;

/**
 * The S9 gate sample: notes as text files in a folder, a list of them, an editor and save. Files
 * are read and written off the UI thread; the results come back through {@code Ui.executor()}.
 *
 * <pre>lazyvm-ui -jar notes.jar [folder]</pre>
 *
 * The notes go in {@code LazyNotes} under the folder, the home folder by default. Options and the
 * {@code attempt=N} LazyOS appends to a desktop app's arguments are skipped.
 */
public final class Notes {
    private final File dir;
    /** One I/O thread, so writes to a note happen in the order they were asked for. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notes-io");
        t.setDaemon(true);
        return t;
    });
    private final ListView list = Ui.list().column("Notes", 0);
    private final Edit editor = Ui.textArea();
    private final Label status = Ui.label("");
    private String[] names = new String[0];
    private String open;

    private Notes(File dir) {
        this.dir = dir;
        list.onSelect(i -> load(i));
    }

    public static void main(String[] args) {
        String base = System.getProperty("user.home", ".");
        for (String a : args) if (!a.startsWith("-") && !a.startsWith("attempt=")) base = a;
        File dir = new File(base, "LazyNotes");
        Notes notes = new Notes(dir);
        Ui.executor().execute(notes::refresh);
        Ui.run(notes.window());
    }

    private Window window() {
        Button add = Ui.button("New").icon("FilePlus").onClick(this::create);
        Button save = Ui.button("Save").icon("Save").onClick(this::save);
        Window w = Ui.window("Notes", 720, 480).content(Ui.column().padding(8).gap(8).add(
            Ui.row().gap(8).add(add, save, status.fill(1)).height(32),
            Ui.row().gap(8).add(list.width(200), editor.fill(1)).fill(1)));
        w.shortcut('s', Window.CTRL, this::save);
        w.shortcut('n', Window.CTRL, this::create);
        return w;
    }

    /** Lists the folder's notes, off the UI thread. */
    private void refresh() {
        CompletableFuture.supplyAsync(() -> {
            dir.mkdirs();
            String[] found = dir.list((d, name) -> name.endsWith(".txt"));
            String[] sorted = found == null ? new String[0] : found;
            Arrays.sort(sorted);
            return sorted;
        }, io).thenAcceptAsync(found -> {
            names = found;
            list.rows(found);
            status.text(found.length + " notes in " + dir);
            System.out.println("NOTES:LIST:" + found.length);
            // Open the first note when none is: selecting from code runs no select action.
            if (open == null && found.length > 0) {
                list.select(0);
                load(0);
            }
        }, Ui.executor());
    }

    private void load(int index) {
        if (index < 0 || index >= names.length) return;
        final String name = names[index];
        CompletableFuture.supplyAsync(() -> read(new File(dir, name)), io).thenAcceptAsync(text -> {
            open = name;
            editor.text(text);
            editor.focus();
            status.text(name);
        }, Ui.executor());
    }

    private void create() {
        int n = names.length + 1;
        while (Arrays.asList(names).contains("note" + n + ".txt")) n++;
        open = "note" + n + ".txt";
        editor.text("");
        editor.focus();
        save();
    }

    private void save() {
        if (open == null) {
            status.text("Pick a note or make a new one");
            return;
        }
        final String name = open;
        final String text = editor.text();
        CompletableFuture.runAsync(() -> write(new File(dir, name), text), io).whenCompleteAsync((ok, failed) -> {
            if (failed != null) {
                status.text("Could not save " + name + ": " + failed.getCause().getMessage());
                System.out.println("NOTES:SAVE:FAIL:" + name);
                return;
            }
            status.text("Saved " + name);
            System.out.println("NOTES:SAVE:PASS:" + name);
            if (!Arrays.asList(names).contains(name)) refresh();
        }, Ui.executor());
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read " + file + ": " + e.getMessage() + ")";
        }
    }

    private static void write(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
