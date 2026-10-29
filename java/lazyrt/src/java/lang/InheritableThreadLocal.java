package java.lang;

import java.util.IdentityHashMap;
import java.util.Map;

/** A ThreadLocal whose values a new thread copies from the thread creating it. */
public class InheritableThreadLocal<T> extends ThreadLocal<T> {
    public InheritableThreadLocal() {
    }

    protected T childValue(T parentValue) {
        return parentValue;
    }

    @SuppressWarnings("unchecked")
    Object childValueOf(Object parentValue) {
        return childValue((T) parentValue);
    }

    Map<ThreadLocal<?>, Object> mapOf(Thread t, boolean create) {
        if (t.inheritableLocals == null && create) {
            t.inheritableLocals = new IdentityHashMap<ThreadLocal<?>, Object>();
        }
        return t.inheritableLocals;
    }
}
