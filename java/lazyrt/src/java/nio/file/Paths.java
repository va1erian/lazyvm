package java.nio.file;

import java.net.URI;

public final class Paths {
    private Paths() {
    }

    public static Path get(String first, String... more) {
        return FileSystems.getDefault().getPath(first, more);
    }

    // Javadoc: a "file" URI, absolute, with a path and no query or fragment.
    public static Path get(URI uri) {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Missing scheme");
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new FileSystemNotFoundException("Provider \"" + uri.getScheme() + "\" not installed");
        }
        if (uri.isOpaque() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("URI is not hierarchical or has a query or fragment");
        }
        String p = uri.getPath();
        String host = uri.getRawAuthority();
        if (host != null && !host.isEmpty()) {
            if (!"\\".equals(FileSystems.getDefault().getSeparator())) {
                throw new IllegalArgumentException("URI has an authority component");
            }
            return get("\\\\" + host + p.replace('/', '\\'));
        }
        if ("\\".equals(FileSystems.getDefault().getSeparator()) && p.length() > 2 && p.charAt(2) == ':') {
            p = p.substring(1);
        }
        return get(p);
    }
}
