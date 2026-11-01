package lang;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Which properties are read at each use, and which only at the first. */
public class FirstUseProperties {
    static final String ELSEWHERE = "Q:/elsewhere";

    static String under(Object path) {
        return path.toString().replace('\\', '/').startsWith(ELSEWHERE) ? "moved" : "kept";
    }

    public static void main(String[] args) throws Exception {
        System.out.println("path " + under(Paths.get("a").toAbsolutePath()));
        File first = File.createTempFile("first", ".tmp");
        Path firstPath = Files.createTempFile("first", ".tmp");
        first.delete();
        Files.delete(firstPath);

        System.setProperty("user.dir", ELSEWHERE);
        System.setProperty("java.io.tmpdir", ELSEWHERE);
        System.out.println("path after " + under(Paths.get("a").toAbsolutePath()));
        System.out.println("file after " + under(new File("a").getAbsolutePath()));
        File second = File.createTempFile("second", ".tmp");
        Path secondPath = Files.createTempFile("second", ".tmp");
        System.out.println("temp file " + under(second.getPath()) + ", temp path " + under(secondPath));
        second.delete();
        Files.delete(secondPath);

        System.setProperties(new Properties());
        System.out.println("path on empty " + under(Paths.get("a").toAbsolutePath()));
        File third = File.createTempFile("third", ".tmp");
        System.out.println("temp on empty " + under(third.getPath()));
        third.delete();
    }
}
