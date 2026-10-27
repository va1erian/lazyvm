package java.lang;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * The class loader of application classes. LazyVM has one application
 * loader; classes of the class library report a null loader, as boot classes
 * do. Defining classes from bytes arrives with S13's reflection work.
 */
public abstract class ClassLoader {
    private static ClassLoader system;
    private final ClassLoader parent;

    protected ClassLoader() {
        this(getSystemClassLoader());
    }

    protected ClassLoader(ClassLoader parent) {
        this.parent = parent;
    }

    public final ClassLoader getParent() {
        return parent;
    }

    public static ClassLoader getSystemClassLoader() {
        if (system == null) {
            system = new AppClassLoader();
        }
        return system;
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, this);
    }

    /** Finds a resource on the class path; `name` has no leading slash. */
    public InputStream getResourceAsStream(String name) {
        byte[] data = lazy.internal.VM.getResource(name);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    public static InputStream getSystemResourceAsStream(String name) {
        return getSystemClassLoader().getResourceAsStream(name);
    }

    /** The one application loader. */
    private static final class AppClassLoader extends ClassLoader {
        AppClassLoader() {
            super(null);
        }
    }
}
