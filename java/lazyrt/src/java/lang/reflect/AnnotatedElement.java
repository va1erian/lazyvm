package java.lang.reflect;

import java.lang.annotation.Annotation;

public interface AnnotatedElement {
    default boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return getAnnotation(annotationClass) != null;
    }

    <T extends Annotation> T getAnnotation(Class<T> annotationClass);

    Annotation[] getAnnotations();

    default <T extends Annotation> T[] getAnnotationsByType(Class<T> annotationClass) {
        return lazy.internal.Annotations.byType(getAnnotations(), annotationClass);
    }

    default <T extends Annotation> T getDeclaredAnnotation(Class<T> annotationClass) {
        if (annotationClass == null) {
            throw new NullPointerException();
        }
        Annotation[] all = getDeclaredAnnotations();
        for (int i = 0; i < all.length; i++) {
            if (all[i].annotationType() == annotationClass) {
                return annotationClass.cast(all[i]);
            }
        }
        return null;
    }

    default <T extends Annotation> T[] getDeclaredAnnotationsByType(Class<T> annotationClass) {
        return lazy.internal.Annotations.byType(getDeclaredAnnotations(), annotationClass);
    }

    Annotation[] getDeclaredAnnotations();
}
