package java.util.concurrent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/**
 * A failure read on another thread than the one that threw it comes as a new object of the same
 * class with the original as its cause, so its stack trace shows the reader too. Only a class
 * whose public constructor taking a {@code Throwable}, or else nothing, can be called gets that;
 * any other failure, or one such a constructor or {@code initCause} refuses, stays the original.
 * The rules and their test lines are in docs/design/concurrent.md ("Failures crossing threads").
 */
final class FailureCopies {
    private FailureCopies() {
    }

    static Throwable of(Throwable original) {
        try {
            Class<?> c = original.getClass();
            // A protected member class is public to the VM, unlike a private one.
            if ((c.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED)) == 0) {
                return original;
            }
            Constructor<?> withCause = find(c, Throwable.class);
            if (withCause != null) {
                return (Throwable) withCause.newInstance(original);
            }
            Constructor<?> plain = find(c);
            if (plain != null) {
                Throwable copy = (Throwable) plain.newInstance();
                copy.initCause(original);
                return copy;
            }
        } catch (Exception unusable) {
            return original;
        }
        return original;
    }

    private static Constructor<?> find(Class<?> c, Class<?>... parameters) {
        try {
            return c.getConstructor(parameters);
        } catch (NoSuchMethodException none) {
            return null;
        }
    }
}
