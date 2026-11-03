package com.dk.dkaiagent.transfer;

/** Failures deliberately carry no source content, SQL, paths, or parser causes. */
public final class TransferException extends IllegalArgumentException {
    public enum Code {
        INVALID_JSON("Invalid transfer JSON"),
        INVALID_UTF8("Invalid transfer UTF-8"),
        UNSUPPORTED_VERSION("Unsupported transfer version"),
        INVALID_STRUCTURE("Invalid transfer structure"),
        INVALID_VALUE("Invalid transfer value"),
        DUPLICATE_RECORD("Duplicate transfer record"),
        INVALID_REFERENCE("Invalid transfer reference"),
        INVALID_MAPPING("Invalid transfer mapping evidence"),
        LIMIT_EXCEEDED("Transfer limit exceeded"),
        IO_ERROR("Transfer I/O failed");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }

    private final Code code;

    public TransferException(Code code) {
        super(code.message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
