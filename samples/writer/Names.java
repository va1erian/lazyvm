import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** File names and capped reads: every file the user picks is untrusted. */
final class Names {
    static final String EXTENSION = "lzw", MARKDOWN = "md", TEXT = "txt";
    static final String[] IMAGES = {"png", "jpg", "jpeg"};
    /** The most a document or a picture may weigh. */
    static final long MAX_DOCUMENT_BYTES = 32L << 20, MAX_IMAGE_BYTES = 16L << 20;
    private static final String UNTITLED = "Untitled";

    private Names() {}

    static String name(String path) {
        return new File(path).getName();
    }

    /** The file name without its extension, or "Untitled". */
    static String stem(String path) {
        if (path == null) return UNTITLED;
        String n = name(path);
        int dot = n.lastIndexOf('.');
        String s = dot > 0 ? n.substring(0, dot) : n;
        return s.isEmpty() ? UNTITLED : s;
    }

    static String displayName(String path) {
        return path == null ? UNTITLED : name(path);
    }

    static boolean hasExtension(String path) {
        return name(path).lastIndexOf('.') > 0;
    }

    /** {@code path}, given {@code ext} when it has no extension of its own. */
    static String withExtension(String path, String ext) {
        return hasExtension(path) ? path : path + "." + ext;
    }

    static boolean isPlainText(String path) {
        String n = name(path).toLowerCase();
        return n.endsWith("." + TEXT);
    }

    /** Where a Markdown export's pictures go: {@code <stem>_images} beside it. */
    static String imagesFolderName(String markdown) {
        String n = name(markdown);
        int dot = n.lastIndexOf('.');
        String s = dot > 0 ? n.substring(0, dot) : n;
        return (s.isEmpty() ? "document" : s) + "_images";
    }

    /** Reads {@code path} whole, refusing more than {@code cap} bytes. */
    static byte[] readCapped(String path, long cap) throws IOException {
        InputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > cap) throw new IOException("the file is larger than " + (cap >> 20) + " MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
