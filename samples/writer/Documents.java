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

import lazy.ui.Dialog;
import lazy.ui.FileDialog;
import lazy.ui.TaskDialog;

/** New, Open, Save, Save As, Export and Insert image, with their pickers and prompts. */
final class Documents {
    /** What runs once unsaved changes are saved or discarded. */
    static final int NEW = 0, OPEN = 1, QUIT = 2;

    private final Writer app;
    private final FileDialog open, save, export, image;
    private final TaskDialog unsaved;
    private final Dialog message;
    final Dialog link;
    /** The step that waits on the unsaved-changes question, or -1. */
    private int after = -1;

    Documents(final Writer app) {
        this.app = app;
        open = app.window.openFile("Open").existing()
            .filter("LazyWriter documents (.lzw)", Names.EXTENSION).filter("Text (.txt)", Names.TEXT)
            .filter("All files").onChoose(p -> chosen(p, this::openPath));
        save = app.window.saveFile("Save As").filter("LazyWriter documents (.lzw)", Names.EXTENSION)
            .onChoose(p -> chosen(p, this::saveChosen));
        export = app.window.saveFile("Export as Markdown").filter("Markdown (.md)", Names.MARKDOWN)
            .onChoose(p -> chosen(p, this::exportTo));
        image = app.window.openFile("Insert image").existing()
            .filter("Images (.png, .jpg, .jpeg)", Names.IMAGES).onChoose(p -> chosen(p, this::imageChosen));
        unsaved = app.window.task("Save changes?",
                "This document has unsaved changes. Save them before you go on?")
            .icon(TaskDialog.WARNING).command("Save").command("Discard").onChoice(this::unsaved);
        message = app.window.message("LazyWriter", "").onClose(s -> closed());
        link = app.window.prompt("Link",
                "The address the selected text links to. Leave it empty to remove the link.", "https://")
            .acceptLabel("Link").onClose(app::linkChosen);
    }

    private interface PathStep {
        void run(String path);
    }

    /** A picker closed: run {@code step} with the path, or just return to the text. */
    private void chosen(String path, PathStep step) {
        app.dialogOpen = false;
        if (path == null) {
            closed();
        } else {
            step.run(path);
        }
    }

    private void closed() {
        app.dialogOpen = false;
        after = -1;
        app.editor.focus();
    }

    /** New, Open or Quit: ask about unsaved changes first. */
    void guard(int next) {
        if (app.dialogOpen) return;
        if (app.dirty) {
            after = next;
            app.dialogOpen = true;
            unsaved.show();
        } else {
            proceed(next);
        }
    }

    private void proceed(int next) {
        if (next == NEW) {
            load("", true, null);
        } else if (next == OPEN) {
            open.folder(startDir());
            app.dialogOpen = true;
            open.show();
        } else {
            app.window.close();
        }
    }

    private void unsaved(int choice) {
        app.dialogOpen = false;
        if (choice == 0) {
            String path = savedPath();
            if (path == null) saveAs();
            else if (saveTo(path)) finish();
        } else if (choice == 1) {
            finish();
        } else {
            after = -1;
            app.editor.focus();
        }
    }

    private void finish() {
        int next = after;
        after = -1;
        if (next >= 0) proceed(next);
        else app.editor.focus();
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
        app.dialogOpen = true;
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

    void save() {
        if (app.dialogOpen) return;
        String path = savedPath();
        if (path == null) saveAs();
        else saveTo(path);
    }

    void saveAs() {
        if (app.dialogOpen) return;
        save.name(Names.stem(app.path) + "." + Names.EXTENSION).folder(startDir());
        app.dialogOpen = true;
        save.show();
    }

    private void saveChosen(String path) {
        if (saveTo(Names.withExtension(path, Names.EXTENSION))) finish();
        else after = -1;
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
        if (app.dialogOpen) return;
        export.name(Names.stem(app.path) + "." + Names.MARKDOWN).folder(startDir());
        app.dialogOpen = true;
        export.show();
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
        if (app.dialogOpen) return;
        image.folder(startDir());
        app.dialogOpen = true;
        image.show();
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
