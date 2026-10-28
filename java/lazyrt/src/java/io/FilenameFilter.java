package java.io;

@FunctionalInterface
public interface FilenameFilter {
    boolean accept(File dir, String name);
}
