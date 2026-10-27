package util;

/** Lives in lib.jar, reached through the app manifest's Class-Path. */
public class Helper {
    public static String greet(String who) {
        return "hello, " + who;
    }
}
