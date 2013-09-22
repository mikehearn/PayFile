package net.plan99.payfile;

public class ProtocolException extends Exception {
    public static enum Code {
        GENERIC,
        NETWORK_MISMATCH,
        INTERNAL_ERROR,
    }

    private Code code;

    public ProtocolException(String msg) {
        super(msg);
        code = Code.GENERIC;
    }

    public ProtocolException(Code code, String msg) {
        super(msg);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
