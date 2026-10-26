package java.lang.invoke;

/**
 * The bootstrap methods javac uses for lambdas and method references.
 * LazyVM links these call sites itself (docs/design/lambdas.md); calling
 * the methods directly is not supported.
 */
public final class LambdaMetafactory {
    public static final int FLAG_SERIALIZABLE = 1 << 0;
    public static final int FLAG_MARKERS = 1 << 1;
    public static final int FLAG_BRIDGES = 1 << 2;

    private LambdaMetafactory() {
    }

    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName,
            MethodType invokedType, MethodType samMethodType, MethodHandle implMethod,
            MethodType instantiatedMethodType) throws LambdaConversionException {
        throw new UnsupportedOperationException("LambdaMetafactory is linked by the VM");
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName,
            MethodType invokedType, Object... args) throws LambdaConversionException {
        throw new UnsupportedOperationException("LambdaMetafactory is linked by the VM");
    }
}
