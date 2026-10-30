package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * The field a field updater works on, checked once when the updater is made,
 * and its check of each receiver. The rules are in docs/design/atomics.md.
 */
final class UpdaterField {
    final Field field;
    /** What every receiver must be an instance of. */
    private final Class<?> receivers;
    /** A protected field reached from a subclass in another package: receivers must be the caller's. */
    private final boolean protectedRule;
    private final Class<?> declaring;

    UpdaterField(Class<?> tclass, String name, Class<?> caller) {
        try {
            field = tclass.getDeclaredField(name);
            checkAccess(tclass, field.getModifiers(), caller);
        } catch (ReflectiveOperationException | NullPointerException e) {
            throw new RuntimeException(e);
        }
        declaring = tclass;
        // Access passed, so a protected field from another package means the caller is a subclass.
        protectedRule = Modifier.isProtected(field.getModifiers()) && !samePackage(tclass, caller);
        receivers = protectedRule ? caller : tclass;
    }

    /** JLS 6.6, at run time: the caller must be able to name the member. */
    private static void checkAccess(Class<?> tclass, int modifiers, Class<?> caller)
            throws IllegalAccessException {
        if (tclass == caller) {
            return;
        }
        boolean samePackage = samePackage(tclass, caller);
        // A protected nested class is public as the VM sees it (JVMS 4.1 has no protected class).
        int classModifiers = tclass.getModifiers();
        boolean classVisible = samePackage || Modifier.isPublic(classModifiers) || Modifier.isProtected(classModifiers);
        boolean memberVisible = Modifier.isPublic(modifiers)
                || (samePackage && !Modifier.isPrivate(modifiers))
                || (Modifier.isProtected(modifiers) && tclass.isAssignableFrom(caller));
        if (!classVisible || !memberVisible) {
            throw new IllegalAccessException("Class " + caller.getName() + " can not access a member of class "
                    + tclass.getName() + " with modifiers \"" + Modifier.toString(modifiers) + "\"");
        }
    }

    private static boolean samePackage(Class<?> a, Class<?> b) {
        return packageOf(a).equals(packageOf(b));
    }

    private static String packageOf(Class<?> c) {
        String name = c.getName();
        return name.substring(0, name.lastIndexOf('.') + 1);
    }

    /** The last checks at creation, after the type checks that differ per updater. */
    void requireVolatileInstance() {
        int modifiers = field.getModifiers();
        if (!Modifier.isVolatile(modifiers)) {
            throw new IllegalArgumentException("Must be volatile type");
        }
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException();
        }
    }

    void checkReceiver(Object receiver) {
        if (receivers.isInstance(receiver)) {
            return;
        }
        if (!protectedRule) {
            throw new ClassCastException();
        }
        // A null receiver fails here too, as a NullPointerException.
        throw new RuntimeException(new IllegalAccessException("Class " + receivers.getName()
                + " can not access a protected member of class " + declaring.getName()
                + " using an instance of " + receiver.getClass().getName()));
    }
}
