package java.lang;

public abstract class VirtualMachineError extends Error {
    protected VirtualMachineError() {
    }

    protected VirtualMachineError(String message) {
        super(message);
    }

    protected VirtualMachineError(String message, Throwable cause) {
        super(message, cause);
    }

    protected VirtualMachineError(Throwable cause) {
        super(cause);
    }
}
