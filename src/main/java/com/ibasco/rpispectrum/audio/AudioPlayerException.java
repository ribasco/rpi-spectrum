package com.ibasco.rpispectrum.audio;

public class AudioPlayerException extends Exception {
    public AudioPlayerException() {
    }

    public AudioPlayerException(String message) {
        super(message);
    }

    public AudioPlayerException(String message, Throwable cause) {
        super(message, cause);
    }

    public AudioPlayerException(Throwable cause) {
        super(cause);
    }

    public AudioPlayerException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
