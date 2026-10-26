package java.lang;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import lazy.internal.Buf;

/** The member lookups and inheritance walks behind Class's reflection methods. */
final class ClassReflection {
    private ClassReflection() {
    }

    // ---- public members, including inherited ones ----------------------

    static Method[] publicMethods(Class<?> c) {
        Buf out = new Buf();
        gatherMethods(c, out, true);
        return (Method[]) out.toArray(Method.class);
    }

    /** Collects c's public methods, then those of its superclass chain and superinterfaces. */
    private static void gatherMethods(Class<?> c, Buf out, boolean top) {
        Method[] declared = c.declaredMethods(true);
        for (int i = 0; i < declared.length; i++) {
            // Static interface methods are not inherited (Class.getMethods Javadoc).
            if (top || !c.isInterface() || !Modifier.isStatic(declared[i].getModifiers())) {
                addMethod(out, declared[i]);
            }
        }
        Class<?> sup = c.isInterface() ? null : c.getSuperclass();
        if (sup != null) {
            gatherMethods(sup, out, false);
        }
        Class<?>[] interfaces = c.getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            gatherMethods(interfaces[i], out, false);
        }
    }

    /** Adds m unless a method with the same signature and return type is there already (a more specific owner wins). */
    private static void addMethod(Buf out, Method m) {
        for (int i = 0; i < out.size(); i++) {
            Method e = (Method) out.get(i);
            if (sameSignature(e, m.getName(), m.getParameterTypes()) && e.getReturnType() == m.getReturnType()) {
                Class<?> owner = e.getDeclaringClass();
                if (owner != m.getDeclaringClass() && owner.isAssignableFrom(m.getDeclaringClass())) {
                    out.set(i, m);
                }
                return;
            }
        }
        out.add(m);
    }

    static Field[] publicFields(Class<?> c) {
        Buf out = new Buf();
        gatherFields(c, out);
        return (Field[]) out.toArray(Field.class);
    }

    private static void gatherFields(Class<?> c, Buf out) {
        Field[] declared = c.declaredFields(true);
        for (int i = 0; i < declared.length; i++) {
            out.add(declared[i]);
        }
        Class<?>[] interfaces = c.getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            gatherFields(interfaces[i], out);
        }
        Class<?> sup = c.isInterface() ? null : c.getSuperclass();
        if (sup != null) {
            gatherFields(sup, out);
        }
    }

    // ---- single member lookups -----------------------------------------

    private static boolean sameParameters(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSignature(Method m, String name, Class<?>[] params) {
        return m.getName().equals(name) && sameParameters(m.getParameterTypes(), params);
    }

    /** The match with the most specific return type among methods that differ only there. */
    static Method pick(Method[] methods, String name, Class<?>[] params) {
        Method best = null;
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (sameSignature(m, name, params)) {
                if (best == null || best.getReturnType().isAssignableFrom(m.getReturnType())) {
                    best = m;
                }
            }
        }
        return best;
    }

    static <T> Constructor<T> pickConstructor(Constructor<T>[] all, Class<?>[] params) {
        for (int i = 0; i < all.length; i++) {
            if (sameParameters(all[i].getParameterTypes(), params)) {
                return all[i];
            }
        }
        return null;
    }

    static Field pickField(Field[] fields, String name) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].getName().equals(name)) {
                return fields[i];
            }
        }
        return null;
    }

    /** "pkg.Cls.name(int, java.lang.String)", the message of NoSuchMethodException. */
    static String describe(Class<?> c, String name, Class<?>[] params) {
        StringBuilder sb = new StringBuilder(c.getName()).append('.').append(name).append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i] == null ? "null" : params[i].getName());
        }
        return sb.append(')').toString();
    }

    // ---- names ----------------------------------------------------------

    /** The text after the last '$' of a binary name, or null when there is none. */
    private static String afterDollar(Class<?> c) {
        String name = c.getName();
        int dollar = name.lastIndexOf('$');
        return dollar < 0 || c.isArray() || c.isPrimitive() ? null : name.substring(dollar + 1);
    }

    static boolean isAnonymous(Class<?> c) {
        String tail = afterDollar(c);
        if (tail == null || tail.length() == 0) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) < '0' || tail.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Local classes are named Outer$1Name: digits, then letters. */
    static boolean isLocal(Class<?> c) {
        String tail = afterDollar(c);
        return tail != null && tail.length() > 1 && tail.charAt(0) >= '0' && tail.charAt(0) <= '9'
                && !isAnonymous(c);
    }

    static String canonicalName(Class<?> c) {
        if (c.isArray()) {
            String component = canonicalName(c.getComponentType());
            return component == null ? null : component + "[]";
        }
        if (isAnonymous(c) || isLocal(c)) {
            return null;
        }
        Class<?> outer = c.getDeclaringClass();
        if (outer == null) {
            return c.getName();
        }
        String outerName = canonicalName(outer);
        return outerName == null ? null : outerName + "." + c.getSimpleName();
    }

    // ---- annotations ----------------------------------------------------

    /** Declared annotations plus the @Inherited ones of the superclass, inherited ones listed first. */
    static Annotation[] withInherited(Class<?> c, Annotation[] declared) {
        Class<?> sup = c.getSuperclass();
        Buf out = new Buf();
        if (sup != null) {
            Annotation[] parent = sup.getAnnotations();
            for (int i = 0; i < parent.length; i++) {
                if (parent[i].annotationType().isAnnotationPresent(Inherited.class)) {
                    out.add(parent[i]);
                }
            }
        }
        for (int i = 0; i < declared.length; i++) {
            int at = indexOfType(out, declared[i].annotationType());
            if (at >= 0) {
                out.set(at, declared[i]);
            } else {
                out.add(declared[i]);
            }
        }
        return (Annotation[]) out.toArray(Annotation.class);
    }

    private static int indexOfType(Buf list, Class<?> type) {
        for (int i = 0; i < list.size(); i++) {
            if (((Annotation) list.get(i)).annotationType() == type) {
                return i;
            }
        }
        return -1;
    }

    /** Rethrows a checked exception from a constructor as Class.newInstance's Javadoc requires. */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
