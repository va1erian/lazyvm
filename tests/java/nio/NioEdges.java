package nio;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Arrays;
import java.util.concurrent.Callable;

/** Edge cases of java.nio found by review: overlapping bulk copies, compact on views, read-only
 *  and self checks, coder results at a full output and from a broken coder, glob classes and
 *  separators, URIs of odd names, and argument checks of Files. */
public class NioEdges {
    static Path root;

    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r.replace(root.toString(), "<T>").replace(File.separatorChar, '/'));
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazynioedge").toRealPath();
        buffers();
        charsets();
        globs();
        files();
        System.out.println("done");
    }

    static String bytes(ByteBuffer b) {
        return Arrays.toString(b.array());
    }

    static void buffers() {
        t("overlapping put forward", () -> { ByteBuffer b = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}); ByteBuffer src = b.duplicate(); src.limit(6); b.position(2); b.put(src); return bytes(b); });
        t("overlapping put back", () -> { ByteBuffer b = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}); ByteBuffer src = b.duplicate(); src.position(2); b.put(src); return bytes(b); });
        t("overlapping int put", () -> { int[] a = {0, 1, 2, 3, 4, 5}; IntBuffer b = IntBuffer.wrap(a); IntBuffer src = b.duplicate(); src.limit(4); b.position(2); b.put(src); return Arrays.toString(a); });
        t("overlapping char put", () -> { char[] a = "abcdef".toCharArray(); CharBuffer b = CharBuffer.wrap(a); CharBuffer src = b.duplicate(); src.limit(4); b.position(2); b.put(src); return new String(a); });
        t("overlapping put through a view", () -> { ByteBuffer b = ByteBuffer.wrap(new byte[16]); for (int i = 0; i < 8; i++) b.putShort(i * 2, (short) i); ShortBuffer v = b.asShortBuffer(); ShortBuffer src = v.duplicate(); src.limit(6); v.position(2); v.put(src); v.clear(); StringBuilder sb = new StringBuilder(); while (v.hasRemaining()) sb.append(v.get()).append(' '); return sb; });
        t("read-only put of itself", () -> { ByteBuffer b = ByteBuffer.allocate(4).asReadOnlyBuffer(); return b.put(b); });
        t("read-only char put of itself", () -> { CharBuffer b = CharBuffer.allocate(4).asReadOnlyBuffer(); return b.put(b); });
        t("put of itself", () -> { IntBuffer b = IntBuffer.allocate(4); return b.put(b); });
        t("char read of itself, empty", () -> { CharBuffer b = CharBuffer.allocate(4); b.position(4); return b.read(b); });
        t("char read of itself", () -> { CharBuffer b = CharBuffer.allocate(4); return b.read(b); });
        t("int hashCode", () -> IntBuffer.wrap(new int[] {1, 2, 3}).hashCode());
        t("long hashCode", () -> LongBuffer.wrap(new long[] {1, 2, 3}).hashCode());
        t("view compact table", NioEdges::compactTable);
        t("direct view compact", () -> { ByteBuffer b = ByteBuffer.allocateDirect(24); b.position(4); IntBuffer v = b.slice().asIntBuffer(); v.compact(); return v.position(); });
        t("zero-capacity view compact", () -> { ByteBuffer b = ByteBuffer.allocate(8); b.position(5); IntBuffer v = b.slice().asIntBuffer(); return v.capacity() + " " + v.compact(); });
        t("read-only view compact", () -> { ByteBuffer b = ByteBuffer.allocate(8); b.position(4); return b.slice().asReadOnlyBuffer().asIntBuffer().compact(); });
        t("view overlap table", NioEdges::overlapTable);
        t("heap view, read-only, put of itself", () -> { IntBuffer v = ByteBuffer.allocate(16).asIntBuffer().asReadOnlyBuffer(); return v.put(v); });
        t("direct view, read-only, put of itself", () -> { IntBuffer v = ByteBuffer.allocateDirect(16).asIntBuffer().asReadOnlyBuffer(); return v.put(v); });
        t("text buffer put of itself", () -> { CharBuffer c = CharBuffer.wrap("abcd"); return c.put(c); });
        t("heap view, read-only, put of no ints", () -> ByteBuffer.allocate(16).asIntBuffer().asReadOnlyBuffer().put(new int[0]).position());
        t("heap view, read-only, bad bounds", () -> ByteBuffer.allocate(16).asCharBuffer().asReadOnlyBuffer().put(new char[4], 3, 2));
        t("heap view, read-only, too many", () -> ByteBuffer.allocate(16).asCharBuffer().asReadOnlyBuffer().put(new char[20]));
        t("array, read-only, put of no ints", () -> IntBuffer.allocate(4).asReadOnlyBuffer().put(new int[0]));
        t("direct view, read-only, read of itself", () -> { CharBuffer c = ByteBuffer.allocateDirect(16).asCharBuffer().asReadOnlyBuffer(); return c.read(c); });
        t("array, read-only, read of itself", () -> { CharBuffer c = CharBuffer.allocate(4).asReadOnlyBuffer(); return c.read(c); });
        t("read into a read-only buffer", () -> { CharBuffer src = CharBuffer.wrap("hello".toCharArray()); try { return src.read(CharBuffer.allocate(3).asReadOnlyBuffer()); } catch (ReadOnlyBufferException e) { return "ROBE, source at " + src.position(); } });
        t("text buffer toString", () -> { StringBuilder sb = new StringBuilder("abcdefgh"); CharBuffer c = CharBuffer.wrap(sb, 2, 6); sb.setCharAt(3, 'X'); return c.toString() + " " + c.subSequence(1, 3); });
        t("text buffer bulk get", () -> { char[] d = new char[4]; CharBuffer.wrap(new StringBuilder("wxyz")).get(d); return new String(d); });
    }

    /**
     * Bulk put between two views of one 24-byte buffer: heap or direct, char, short or int
     * views, the same or the other byte order, the target 1 to 6 bytes after or before the
     * source; a hash of the bytes for each.
     */
    static String overlapTable() {
        StringBuilder sb = new StringBuilder();
        for (int direct = 0; direct < 2; direct++) for (int kind = 0; kind < 3; kind++) for (int order = 0; order < 2; order++) {
            sb.append(direct == 1 ? " d" : " h").append("csi".charAt(kind)).append(order).append(':');
            for (int shift = -6; shift <= 6; shift++) {
                if (shift == 0) continue;
                ByteBuffer root = direct == 1 ? ByteBuffer.allocateDirect(24) : ByteBuffer.allocate(24);
                for (int i = 0; i < 24; i++) root.put(i, (byte) i);
                ByteBuffer from = root.duplicate();
                from.position(8);
                ByteBuffer to = root.duplicate();
                to.position(8 + shift);
                if (order == 1) to.order(ByteOrder.LITTLE_ENDIAN);
                if (kind == 0) { CharBuffer a = from.asCharBuffer(); a.limit(4); to.asCharBuffer().put(a); }
                else if (kind == 1) { ShortBuffer a = from.asShortBuffer(); a.limit(4); to.asShortBuffer().put(a); }
                else { IntBuffer a = from.asIntBuffer(); a.limit(2); to.asIntBuffer().put(a); }
                byte[] all = new byte[24];
                for (int i = 0; i < 24; i++) all[i] = root.get(i);
                sb.append(Integer.toHexString(Arrays.hashCode(all))).append(',');
            }
        }
        return sb.toString();
    }

    /** Which views of a sliced 40-byte heap buffer compact, by slice offset and view limit. */
    static String compactTable() {
        StringBuilder sb = new StringBuilder();
        for (int size : new int[] {2, 4, 8}) {
            sb.append(" s").append(size).append(':');
            for (int off = 0; off <= 17; off += 1) {
                ByteBuffer b = ByteBuffer.allocate(40);
                b.position(off);
                ByteBuffer s = b.slice();
                Buffer v = size == 2 ? s.asCharBuffer() : size == 4 ? s.asIntBuffer() : s.asLongBuffer();
                for (int cut = 0; cut <= 2 && cut <= v.capacity(); cut++) {
                    v.clear();
                    v.limit(v.capacity() - cut);
                    try {
                        if (v instanceof CharBuffer) ((CharBuffer) v).compact(); else if (v instanceof IntBuffer) ((IntBuffer) v).compact(); else ((LongBuffer) v).compact();
                        sb.append('o');
                    } catch (IllegalArgumentException e) {
                        sb.append('X');
                    }
                }
                sb.append(',');
            }
        }
        return sb.toString();
    }

    static String result(CoderResult r, Buffer in, Buffer out) {
        return r + " in=" + in.position() + " out=" + out.position();
    }

    static void charsets() {
        Charset latin = StandardCharsets.ISO_8859_1;
        t("latin1 encode, full then unmappable", () -> { CharBuffer in = CharBuffer.wrap("ab€"); ByteBuffer out = ByteBuffer.allocate(2); return result(latin.newEncoder().encode(in, out, true), in, out); });
        t("latin1 encode, full then mappable", () -> { CharBuffer in = CharBuffer.wrap("abc"); ByteBuffer out = ByteBuffer.allocate(2); return result(latin.newEncoder().encode(in, out, true), in, out); });
        t("latin1 encode on arrays, full then unmappable", () -> { CharBuffer in = CharBuffer.wrap(("ab" + (char) 0x20AC).toCharArray()); ByteBuffer out = ByteBuffer.allocate(2); return result(latin.newEncoder().encode(in, out, true), in, out); });
        t("latin1 encode on arrays, room then unmappable", () -> { CharBuffer in = CharBuffer.wrap(("ab" + (char) 0x20AC).toCharArray()); ByteBuffer out = ByteBuffer.allocate(3); return result(latin.newEncoder().encode(in, out, true), in, out); });
        t("canEncode after a failed encode", () -> { CharsetEncoder e = StandardCharsets.US_ASCII.newEncoder(); try { e.encode(CharBuffer.wrap("a" + (char) 0xE9)); } catch (CharacterCodingException x) { } return e.canEncode("a"); });
        t("canEncode after an encode", () -> { CharsetEncoder e = StandardCharsets.US_ASCII.newEncoder(); e.encode(CharBuffer.wrap("a")); return e.canEncode("a") + " " + e.encode(CharBuffer.allocate(0), ByteBuffer.allocate(1), true); });
        t("canEncode(char) while coding", () -> { CharsetEncoder e = StandardCharsets.US_ASCII.newEncoder(); ByteBuffer out = ByteBuffer.allocate(8); e.encode(CharBuffer.wrap("ab"), out, false); return e.canEncode('a') + " " + e.canEncode((char) 0xE9) + " " + e.encode(CharBuffer.wrap("c"), out, true); });
        t("canEncode(char) after flush", () -> { CharsetEncoder e = StandardCharsets.UTF_8.newEncoder(); ByteBuffer out = ByteBuffer.allocate(8); e.encode(CharBuffer.wrap("ab"), out, true); e.flush(out); String r = e.canEncode('a') + " " + e.canEncode((char) 0xD800) + " " + e.flush(out); try { e.encode(CharBuffer.wrap("z"), out, true); } catch (IllegalStateException x) { r += " " + x.getMessage(); } return r; });
        t("replace in place, heap", () -> { byte[] b = new byte[30]; for (int i = 0; i < 30; i++) b[i] = (byte) (i % 3 == 0 ? 0xC3 : i % 3 == 1 ? 'a' : 0xA9); return new String(b, StandardCharsets.UTF_8).hashCode(); });
        t("replace until the output is full", () -> { CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).replaceWith("?"); CharBuffer out = CharBuffer.allocate(2); ByteBuffer in = ByteBuffer.wrap(new byte[] {(byte) 0xFF, 'a', (byte) 0xFF}); CoderResult r = d.decode(in, out, true); return r + " in=" + in.position() + " " + new String(out.array(), 0, out.position()); });
        t("ascii encode, full then unmappable", () -> { CharBuffer in = CharBuffer.wrap("ab€"); ByteBuffer out = ByteBuffer.allocate(2); return result(StandardCharsets.US_ASCII.newEncoder().encode(in, out, true), in, out); });
        t("direct decode with errors", () -> { ByteBuffer d = ByteBuffer.allocateDirect(300); for (int i = 0; i < 300; i++) d.put(i, (byte) (i % 3 == 0 ? 0xFF : 'a')); CharBuffer c = StandardCharsets.UTF_8.decode(d); return c.remaining() + " " + c.charAt(0) + c.charAt(1); });
        t("string encode with errors", () -> { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 300; i++) sb.append(i % 3 == 0 ? 'é' : 'a'); ByteBuffer e = StandardCharsets.US_ASCII.encode(sb.toString()); return e.remaining() + " " + e.get(0) + " " + e.get(1); });
        t("decoder averageCharsPerByte 0", () -> new CharsetDecoder(latin, 0f, 1f) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) { return CoderResult.UNDERFLOW; }
        });
        t("decoder maxCharsPerByte 0", () -> new CharsetDecoder(latin, 1f, 0f) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) { return CoderResult.UNDERFLOW; }
        });
        t("encoder averageBytesPerChar -1", () -> new CharsetEncoder(latin, -1f, 1f) {
            protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) { return CoderResult.UNDERFLOW; }
        });
        t("coder throwing IllegalStateException", () -> new CharsetDecoder(latin, 1f, 1f) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) { throw new IllegalStateException("inner"); }
        }.decode(ByteBuffer.allocate(1), CharBuffer.allocate(1), true));
        t("coder throwing BufferUnderflowException", () -> new CharsetDecoder(latin, 1f, 1f) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) { throw new BufferUnderflowException(); }
        }.decode(ByteBuffer.allocate(1), CharBuffer.allocate(1), true));
    }

    static String glob(String pattern, String... paths) {
        try {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            StringBuilder sb = new StringBuilder();
            for (String p : paths) sb.append(p).append('=').append(m.matches(Paths.get(p))).append(' ');
            return sb.toString().trim();
        } catch (java.util.regex.PatternSyntaxException e) {
            // The index of an empty class is in the oracle's own regex: not compared (nio-file.md section 3).
            return "PSE " + e.getDescription() + (pattern.contains("[]") || pattern.contains("[!]") ? "" : " at " + e.getIndex());
        }
    }

    static void globs() {
        t("glob [!b] and the separator", () -> glob("a[!b]c", "axc", "abc", "a/c"));
        t("glob *[!~]* across names", () -> glob("*[!~]*", "dir/file", "file"));
        t("glob [/]", () -> glob("[/]"));
        t("glob [a/b]", () -> glob("x[a/b]"));
        t("glob []a]", () -> glob("[]a]", "]", "a"));
        t("glob [!]a]", () -> glob("[!]a]", "]", "b"));
        t("glob []", () -> glob("[]"));
        t("glob unclosed [", () -> glob("a[bc"));
        t("glob unclosed {", () -> glob("a{bc"));
    }

    static void files() throws IOException {
        Path f = Files.write(root.resolve("f"), new byte[] {1, 2});
        Path d = Files.createDirectory(root.resolve("d"));
        t("FileTime 100 ms", () -> FileTime.fromMillis(100));
        t("FileTime 1 ms", () -> FileTime.fromMillis(1));
        t("FileTime 1234567 us", () -> FileTime.from(1234567, java.util.concurrent.TimeUnit.MICROSECONDS));
        t("dir set modified time", () -> { Files.setLastModifiedTime(d, FileTime.fromMillis(1000000000000L)); return Files.getLastModifiedTime(d).toMillis(); });
        t("small negative time", () -> { Files.setLastModifiedTime(f, FileTime.fromMillis(-1000)); return Files.getLastModifiedTime(f).toMillis(); });
        t("set access time", () -> { Files.setAttribute(f, "lastAccessTime", FileTime.fromMillis(1200000000000L)); return Files.getAttribute(f, "lastAccessTime"); });
        t("getAttribute *", () -> Files.getAttribute(f, "*"));
        t("getAttribute two names", () -> Files.getAttribute(f, "size,isDirectory"));
        t("setAttribute unknown", () -> { Files.setAttribute(f, "basic:x", 1); return "set"; });
        t("temp prefix with a separator", () -> Files.createTempFile(root, "a/b", null));
        t("temp suffix with a separator", () -> Files.createTempFile(root, "ab", "/x"));
        t("temp dir prefix climbing", () -> Files.createTempDirectory(root, "../x"));
        t("move into itself", () -> { try { Files.move(d, d.resolve("sub")); return "moved"; } catch (FileSystemException e) { return e.getClass().getName() + " sub=" + Files.exists(d.resolve("sub")) + " d=" + Files.isDirectory(d); } });
        t("move into a missing directory", () -> Files.move(f, root.resolve("missing").resolve("x")));
        t("newInputStream null option", () -> Files.newInputStream(f, (OpenOption) null));
        t("newOutputStream copy option", () -> Files.newOutputStream(f, new OpenOption() {}));
        t("copy stream atomic", () -> Files.copy(new ByteArrayInputStream(new byte[1]), root.resolve("g"), StandardCopyOption.ATOMIC_MOVE));
        t("copy null option", () -> Files.copy(f, root.resolve("g"), (CopyOption) null));
        t("copy below a file", () -> Files.copy(f, f.resolve("x")));
        t("toRealPath of a missing path", () -> { try { return Paths.get("nope1", "..", "nope2", ".", "x").toRealPath(); } catch (NoSuchFileException e) { return e.getFile().equals(Paths.get("nope2", "x").toAbsolutePath().toString()); } });
        t("toUri nbsp and line separator", () -> { String u = root.resolve("a b c").toUri().getRawPath(); return u.substring(u.lastIndexOf('/')); });
        t("toUri brackets", () -> { String u = root.resolve("u[v]").toUri().getRawPath(); return u.substring(u.lastIndexOf('/')); });
        for (Path p : new Path[] {f, d}) Files.deleteIfExists(p);
        System.out.println("left " + Arrays.toString(root.toFile().list()));
        Files.delete(root);
    }
}
