package lazy.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Superclass of the VM-generated annotation implementations. Names and values are in the
 * order of the annotation type's declared methods.
 */
public abstract class AnnotationBase implements Annotation {
    private final Class<? extends Annotation> type;
    private final String[] names;
    private final Object[] values;
    /** Member indexes in the order toString lists them; null means declaration order. */
    int[] printOrder;

    protected AnnotationBase(Class<? extends Annotation> type, String[] names, Object[] values) {
        this.type = type;
        this.names = names;
        this.values = values;
    }

    /** The stored value of member `i`; arrays are copied so callers cannot change the annotation. */
    protected final Object value(int i) {
        return AnnotationValues.copy(values[i]);
    }

    public final Class<? extends Annotation> annotationType() {
        return type;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!type.isInstance(o)) {
            return false;
        }
        Method[] members = type.getDeclaredMethods();
        for (int i = 0; i < values.length; i++) {
            if (!AnnotationValues.equal(values[i], memberOf(o, members, i))) {
                return false;
            }
        }
        return true;
    }

    private Object memberOf(Object o, Method[] members, int i) {
        if (o instanceof AnnotationBase && ((AnnotationBase) o).type == type) {
            return ((AnnotationBase) o).values[i];
        }
        try {
            members[i].setAccessible(true);
            return members[i].invoke(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public int hashCode() {
        int sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += (127 * names[i].hashCode()) ^ AnnotationValues.hash(values[i]);
        }
        return sum;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("@").append(type.getName()).append('(');
        for (int k = 0; k < values.length; k++) {
            int i = printOrder == null ? k : printOrder[k];
            if (k > 0) {
                sb.append(", ");
            }
            sb.append(names[i]).append('=').append(AnnotationValues.text(values[i]));
        }
        return sb.append(')').toString();
    }
}
