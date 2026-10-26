package java.lang.reflect;

import java.lang.annotation.Annotation;

/** Holds the accessibility flag; LazyVM does no access checks beyond it. */
public class AccessibleObject implements AnnotatedElement {
    boolean override;

    protected AccessibleObject() {
    }

    public static void setAccessible(AccessibleObject[] array, boolean flag) {
        for (int i = 0; i < array.length; i++) {
            array[i].override = flag;
        }
    }

    public void setAccessible(boolean flag) {
        override = flag;
    }

    public boolean isAccessible() {
        return override;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return lazy.internal.Annotations.find(getAnnotations(), annotationClass);
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return getAnnotation(annotationClass) != null;
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    public Annotation[] getDeclaredAnnotations() {
        return new Annotation[0];
    }
}
