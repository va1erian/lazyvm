package java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import lazy.internal.FileSystem;

/**
 * The "basic" attribute view (nio-file.md NF6), with the three time stamps in
 * microseconds. A time the platform does not keep reports the last-modified
 * time, a default the Javadoc allows.
 */
final class FileAttrs implements BasicFileAttributes {
    private static final String[] NAMES = {"lastModifiedTime", "lastAccessTime", "creationTime", "size",
        "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey"};

    private final int bits;
    private final long size;
    private final FileTime modified;
    private final FileTime accessed;
    private final FileTime created;

    private FileAttrs(int bits, long size, String name) {
        this.bits = bits;
        this.size = size;
        this.modified = time(name, FileSystem.MODIFIED, null);
        this.accessed = time(name, FileSystem.ACCESSED, modified);
        this.created = time(name, FileSystem.CREATED, modified);
    }

    private static FileTime time(String name, int which, FileTime otherwise) {
        long us = FileSystem.fileTime(name, which);
        if (us == Long.MIN_VALUE) {
            return otherwise != null ? otherwise : FileTime.fromMillis(0);
        }
        return FileTime.from(us, java.util.concurrent.TimeUnit.MICROSECONDS);
    }

    static FileAttrs read(Path p) throws NoSuchFileException {
        String name = p.toString();
        int bits = FileSystem.attributes(name);
        if (bits == 0) {
            throw new NoSuchFileException(name);
        }
        return new FileAttrs(bits, FileSystem.length(name), name);
    }

    public FileTime lastModifiedTime() {
        return modified;
    }

    public FileTime lastAccessTime() {
        return accessed;
    }

    public FileTime creationTime() {
        return created;
    }

    public boolean isRegularFile() {
        return (bits & FileSystem.REGULAR) != 0;
    }

    public boolean isDirectory() {
        return (bits & FileSystem.DIRECTORY) != 0;
    }

    public boolean isSymbolicLink() {
        return (bits & FileSystem.LINK) != 0;
    }

    public boolean isOther() {
        return (bits & (FileSystem.REGULAR | FileSystem.DIRECTORY | FileSystem.LINK)) == 0;
    }

    public long size() {
        return size;
    }

    public Object fileKey() {
        return null;
    }

    private Object get(String name) {
        switch (name) {
            case "lastModifiedTime":
                return modified;
            case "lastAccessTime":
                return accessed;
            case "creationTime":
                return created;
            case "size":
                return size;
            case "isRegularFile":
                return isRegularFile();
            case "isDirectory":
                return isDirectory();
            case "isSymbolicLink":
                return isSymbolicLink();
            case "isOther":
                return isOther();
            default:
                return null;
        }
    }

    /** The names after "view:", checking the view (Spec NF6: only "basic"). */
    private static String names(String attributes) {
        int colon = attributes.indexOf(':');
        if (colon < 0) {
            return attributes;
        }
        String view = attributes.substring(0, colon);
        if (!view.equals("basic")) {
            throw new UnsupportedOperationException("View '" + view + "' not available");
        }
        return attributes.substring(colon + 1);
    }

    private static boolean known(String name) {
        for (String n : NAMES) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // Spec NF6: the named keys only; an unknown name is "'x' not recognized".
    static Map<String, Object> readMap(Path p, String attributes) throws IOException {
        String list = names(attributes);
        Map<String, Object> out = new HashMap<>();
        FileAttrs a = null;
        for (String name : list.split(",")) {
            if (!name.equals("*") && !known(name)) {
                throw new IllegalArgumentException("'" + name + "' not recognized");
            }
            if (a == null) {
                a = read(p);
            }
            for (String n : NAMES) {
                if (name.equals("*") || name.equals(n)) {
                    out.put(n, a.get(n));
                }
            }
        }
        return out;
    }

    // Observed: one attribute only; "*" or a list is IllegalArgumentException(attribute).
    static Object getOne(Path p, String attribute) throws IOException {
        String name = names(attribute);
        if (name.equals("*") || name.indexOf(',') >= 0) {
            throw new IllegalArgumentException(attribute);
        }
        return readMap(p, attribute).get(name);
    }

    // Spec NF6: the three times can be set. Observed: other names are "'basic:x' not recognized".
    static void set(Path p, String attribute, Object value) throws IOException {
        String name = names(attribute);
        int which = name.equals("lastModifiedTime") ? FileSystem.MODIFIED
                : name.equals("lastAccessTime") ? FileSystem.ACCESSED
                : name.equals("creationTime") ? FileSystem.CREATED : -1;
        if (which < 0) {
            throw new IllegalArgumentException("'basic:" + name + "' not recognized");
        }
        setTime(p, which, (FileTime) value);
    }

    /** Observed: a null time changes nothing; a time the OS refuses is FileSystemException. */
    static void setTime(Path p, int which, FileTime time) throws IOException {
        FileAccess.requireExists(p);
        if (time == null) {
            return;
        }
        try {
            FileSystem.setFileTime(p.toString(), which, time.to(java.util.concurrent.TimeUnit.MICROSECONDS));
        } catch (IOException e) {
            throw new FileSystemException(p.toString(), null, e.getMessage());
        }
    }
}
