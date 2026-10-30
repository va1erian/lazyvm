package thread;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The public and protected members of java.util.concurrent.atomic, exactly. */
public class AtomicApi {
    static final String[] NAMES = {
        "AtomicBoolean", "AtomicInteger", "AtomicIntegerArray", "AtomicIntegerFieldUpdater", "AtomicLong",
        "AtomicLongArray", "AtomicLongFieldUpdater", "AtomicMarkableReference", "AtomicReference",
        "AtomicReferenceArray", "AtomicReferenceFieldUpdater", "AtomicStampedReference", "DoubleAccumulator",
        "DoubleAdder", "LongAccumulator", "LongAdder",
    };

    static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    static void describe(Class<?> c) {
        System.out.println(Modifier.toString(c.getModifiers()) + " " + c.getName());
        List<String> lines = new ArrayList<>();
        for (Class<?> i : c.getInterfaces()) {
            lines.add("implements " + i.getName());
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (visible(k.getModifiers())) {
                lines.add(k.toGenericString());
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (visible(m.getModifiers()) && !m.isSynthetic()) {
                lines.add(m.toGenericString());
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (visible(f.getModifiers())) {
                lines.add(f.toGenericString());
            }
        }
        Collections.sort(lines);
        for (String line : lines) {
            System.out.println("  " + line);
        }
        System.out.println("  instance of Number: " + Number.class.isAssignableFrom(c));
    }

    public static void main(String[] args) throws Exception {
        for (String name : NAMES) {
            describe(Class.forName("java.util.concurrent.atomic." + name));
        }
    }
}
