package java.lang;

/** Thrown in a thread by the deprecated Thread.stop; never thrown by LazyVM. */
public class ThreadDeath extends Error {
    public ThreadDeath() {
    }
}
