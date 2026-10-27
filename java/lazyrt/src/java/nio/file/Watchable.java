package java.nio.file;

/**
 * An object a watch service can watch. LazyVM has no watch services
 * (nio-file.md section 4), so the register methods are left out; the type
 * is here so that Path has its Java SE 8 supertypes.
 */
public interface Watchable {
}
