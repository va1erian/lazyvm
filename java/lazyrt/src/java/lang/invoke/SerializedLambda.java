package java.lang.invoke;

import java.io.Serializable;

/** Plain data holder describing a serializable lambda; javac's generated code reads it. */
public final class SerializedLambda implements Serializable {
    private final Class<?> capturingClass;
    private final String functionalInterfaceClass;
    private final String functionalInterfaceMethodName;
    private final String functionalInterfaceMethodSignature;
    private final String implClass;
    private final String implMethodName;
    private final String implMethodSignature;
    private final int implMethodKind;
    private final String instantiatedMethodType;
    private final Object[] capturedArgs;

    public SerializedLambda(Class<?> capturingClass, String functionalInterfaceClass,
            String functionalInterfaceMethodName, String functionalInterfaceMethodSignature,
            int implMethodKind, String implClass, String implMethodName, String implMethodSignature,
            String instantiatedMethodType, Object[] capturedArgs) {
        this.capturingClass = capturingClass;
        this.functionalInterfaceClass = functionalInterfaceClass;
        this.functionalInterfaceMethodName = functionalInterfaceMethodName;
        this.functionalInterfaceMethodSignature = functionalInterfaceMethodSignature;
        this.implMethodKind = implMethodKind;
        this.implClass = implClass;
        this.implMethodName = implMethodName;
        this.implMethodSignature = implMethodSignature;
        this.instantiatedMethodType = instantiatedMethodType;
        this.capturedArgs = capturedArgs.clone();
    }

    public String getCapturingClass() {
        return capturingClass.getName().replace('.', '/');
    }

    public String getFunctionalInterfaceClass() {
        return functionalInterfaceClass;
    }

    public String getFunctionalInterfaceMethodName() {
        return functionalInterfaceMethodName;
    }

    public String getFunctionalInterfaceMethodSignature() {
        return functionalInterfaceMethodSignature;
    }

    public String getImplClass() {
        return implClass;
    }

    public String getImplMethodName() {
        return implMethodName;
    }

    public String getImplMethodSignature() {
        return implMethodSignature;
    }

    public int getImplMethodKind() {
        return implMethodKind;
    }

    public final String getInstantiatedMethodType() {
        return instantiatedMethodType;
    }

    public int getCapturedArgCount() {
        return capturedArgs.length;
    }

    public Object getCapturedArg(int i) {
        return capturedArgs[i];
    }
}
