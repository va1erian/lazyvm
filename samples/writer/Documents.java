import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import lazy.ui.Dialog;
import lazy.ui.FileDialog;
import lazy.ui.TaskDialog;
import lazy.ui.Ui;

/** New, Open, Save, Save As, Export and Insert image, with their pickers and prompts. */
final class Documents {
    private final Writer app;
    private final FileDialog open, save, export, image;
    private final TaskDialog unsaved;
    private final Dialog message;
    final Dialog link;

    Documents(final Writer app) {
        this.app = app;
        open = app.window.openFile("Open").existing()
            .filter("LazyWriter documents (.lzw)", Names.EXTENSION).filter("Text (.txt)", Names.TEXT)
            .filter("All files");
        save = app.window.saveFile("Save As").filter("LazyWriter documents (.lzw)", Names.EXTENSION);
        export = app.window.saveFile("Export as Markdown").filter("Markdown (.md)", Names.MARKDOWN);
        image = app.window.openFile("Insert image").existing()
            .filter("Images (.png, .jpg, .jpeg)", Names.IMAGES);
        unsaved = app.window.task("Save changes?",
                "This document has unsaved changes. Save them before you go on?")
            .icon(TaskDialog.WARNING).command("Save").command("Discard");
        message = app.window.message("LazyWriter", "").onClose(s -> app.editor.focus());
        link = app.window.prompt("Link",
                "The address the selected text links to. Leave it empty to remove the link.", "https://")
            .acceptLabel("Link").onClose(app::linkChosen);
    }

    /**
     * Hands a failure of {@code stage} to the UI loop, which stops the program with the error
     * dialog, as it does for a callback's: a stage would keep it to itself. A cancelled stage
     * (a dialog that did not show, or a closed window) is no failure.
     */
    private static <T> CompletionStage<T> surface(CompletionStage<T> stage) {
        stage.whenComplete((v, t) -> {
            Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
            if (c == null || c instanceof CancellationException) return;
            Ui.later(() -> {
                if (c instanceof Error) throw (Error) c;
                throw c instanceof RuntimeException ? (RuntimeException) c : new RuntimeException(c);
            });
        });
        return stage;
    }

    /** Shows {@code picker} and runs {@code step} with the path, or returns to the text. */
    private void pick(FileDialog picker, Consumer<String> step) {
        surface(picker.folder(startDir()).show().thenAccept(p -> {
            if (p == null) app.editor.focus();
            else step.accept(p);
        }));
    }

    /** New, Open or Quit: runs {@code next} once unsaved changes are saved or discarded. */
    void guard(Runnable next) {
        if (!app.dirty) {
            next.run();
            return;
        }
        surface(unsaved.show().thenAccept(choice -> {
            if (choice == 0) surface(save().thenAccept(saved -> {
                if (saved) next.run();
            }));
            else if (choice == 1) next.run();
            else app.editor.focus();
        }));
    }

    void newDocument() {
        load("", true, null);
    }

    void open() {
        pick(open, this::openPath);
    }

    /** Where pickers start: the document's folder, or the home folder. */
    private String startDir() {
        File parent = app.path == null ? null : new File(app.path).getAbsoluteFile().getParentFile();
        return parent != null ? parent.getPath() : System.getProperty("user.home", ".");
    }

    /** The path Save writes to: none for a new document or a .txt one. */
    private String savedPath() {
        return app.path != null && !Names.isPlainText(app.path) ? app.path : null;
    }

    /** Shows {@code text} (plain text when {@code plain}, else the native JSON) as the document. */
    private void load(String text, boolean plain, String path) {
        if (plain) app.editor.plainText(text);
        else app.editor.json(text);
        app.path = path;
        app.dirty = false;
        app.summary = null;
        app.status.text(2, Writer.wordsLabel(app.editor.words()));
        app.refreshTitle();
        app.refreshTable();
        app.refreshPages();
        app.editor.focus();
        app.selection(app.editor.startStyle());
    }

    private void fail(String what, String path, String error) {
        message.text(what + " " + path + ": " + error);
        message.show();
    }

    void openPath(String path) {
        try {
            byte[] bytes = Names.readCapped(path, Names.MAX_DOCUMENT_BYTES);
            boolean plain = Names.isPlainText(path);
            load(plain ? new String(bytes, StandardCharsets.UTF_8) : strictUtf8(bytes), plain, path);
            System.out.println("WRITER:OPEN:PASS:" + path);
        } catch (IOException | IllegalStateException e) {
            System.out.println("WRITER:OPEN:FAIL:" + path);
            fail("Could not open", path, e.getMessage());
        }
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("not a LazyWriter document (not text)");
        }
    }

    /** Saves to the document's file, or asks for one; the stage says whether it saved. */
    CompletionStage<Boolean> save() {
        String path = savedPath();
        return path == null ? saveAs() : CompletableFuture.completedFuture(saveTo(path));
    }

    CompletionStage<Boolean> saveAs() {
        return surface(save.name(Names.stem(app.path) + "." + Names.EXTENSION).folder(startDir()).show()
            .thenApply(p -> p != null ? saveTo(Names.withExtension(p, Names.EXTENSION)) : refocus()));
    }

    /** Back to the text, having saved nothing. */
    private boolean refocus() {
        app.editor.focus();
        return false;
    }

    private boolean saveTo(String path) {
        try {
            write(path, app.editor.json().getBytes(StandardCharsets.UTF_8));
            System.out.println("WRITER:SAVE:PASS:" + path);
            app.path = path;
            app.dirty = false;
            app.refreshTitle();
            app.editor.focus();
            return true;
        } catch (IOException e) {
            System.out.println("WRITER:SAVE:FAIL:" + path);
            fail("Could not save", path, e.getMessage());
            return false;
        }
    }

    /**
     * Writes a sibling file, then moves it over {@code path} in one step, so a failed save leaves
     * the old file whole.
     */
    static void write(String path, byte[] bytes) throws IOException {
        File target = new File(path);
        File temp = new File(target.getAbsoluteFile().getParentFile(), "." + target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
    }

    void export() {
        pick(export.name(Names.stem(app.path) + "." + Names.MARKDOWN), this::exportTo);
    }

    /**
     * Writes the document as Markdown, its pictures as PNGs in {@code <stem>_images/} beside it
     * (made only when there is one), linked relatively.
     */
    private void exportTo(String chosen) {
        String path = Names.withExtension(chosen, Names.MARKDOWN);
        String folder = Names.imagesFolderName(path);
        try {
            List<byte[]> pictures = new ArrayList<byte[]>();
            String markdown = app.editor.markdown(folder + "/", pictures);
            if (!pictures.isEmpty()) {
                File dir = new File(new File(path).getAbsoluteFile().getParentFile(), folder);
                // A planted link would send the pictures somewhere else.
                if (Files.isSymbolicLink(dir.toPath())) throw new IOException(dir + " is a symbolic link");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
                for (int i = 0; i < pictures.size(); i++)
                    write(new File(dir, (i + 1) + ".png").getPath(), pictures.get(i));
            }
            write(path, markdown.getBytes(StandardCharsets.UTF_8));
            System.out.println("WRITER:EXPORT:PASS:" + path);
            app.status.text(0, "Exported " + Names.displayName(path));
            app.editor.focus();
        } catch (IOException | IllegalStateException e) {
            System.out.println("WRITER:EXPORT:FAIL:" + path);
            fail("Could not export", path, e.getMessage());
        }
    }

    void insertImage() {
        pick(image, this::imageChosen);
    }

    private void imageChosen(String path) {
        try {
            app.editor.insertImage(Names.readCapped(path, Names.MAX_IMAGE_BYTES), Names.stem(path));
            System.out.println("WRITER:IMAGE:PASS:" + path);
            app.refocus();
        } catch (IOException | IllegalStateException e) {
            System.out.println("WRITER:IMAGE:FAIL:" + path);
            fail("Could not insert", path, e.getMessage());
        }
    }
}
