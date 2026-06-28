package com.pophie.voice;

/** SDK 错误回调。 */
public final class VoiceError {

    public enum Code {
        PERMISSION_DENIED,
        MIC_INIT_FAILED,
        MODEL_LOAD_FAILED,
        MODEL_DOWNLOAD_FAILED,
        NOT_ENROLLED,
        INTERNAL,
    }

    public final Code code;
    public final String message;
    public final Throwable cause;

    public VoiceError(Code code, String message, Throwable cause) {
        this.code = code;
        this.message = message;
        this.cause = cause;
    }

    public VoiceError(Code code, String message) {
        this(code, message, null);
    }

    @Override
    public String toString() {
        return "VoiceError{" + code + ": " + message + (cause != null ? " / " + cause : "") + "}";
    }
}
