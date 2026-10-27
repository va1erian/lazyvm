package lazy.internal;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Method;

/** Lookups over an array of annotations, shared by every AnnotatedElement. */
public final class Annotations {
    private Annotations() {
    }

    /** The annotation of exactly this type, or null. */
    public static <T extends Annotation> T find(Annotation[] all, Class<T> type) {
        if (type == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < all.length; i++) {
            if (all[i].annotationType() == type) {
                return type.cast(all[i]);
            }
        }
        return null;
    }

    /**
     * The annotations of `type`, directly present or inside the container of a repeatable type,
     * in the order they appear.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Annotation> T[] byType(Annotation[] all, Class<T> type) {
        if (type == null) {
            throw new NullPointerException();
        }
        Repeatable repeatable = type.getAnnotation(Repeatable.class);
        Class<? extends Annotation> container = repeatable == null ? null : repeatable.value();
        Buf out = new Buf();
        for (int i = 0; i < all.length; i++) {
            Class<? extends Annotation> t = all[i].annotationType();
            if (t == type) {
                out.add(all[i]);
            } else if (t == container) {
                addContained(out, all[i], container);
            }
        }
        return (T[]) out.toArray(type);
    }

    private static void addContained(Buf out, Annotation container, Class<? extends Annotation> type) {
        try {
            Method value = type.getDeclaredMethod("value");
            value.setAccessible(true);
            Object[] inner = (Object[]) value.invoke(container);
            for (int i = 0; i < inner.length; i++) {
                out.add(inner[i]);
            }
        } catch (ReflectiveOperationException e) {
            throw new java.lang.annotation.AnnotationFormatError(e);
        }
    }
}
