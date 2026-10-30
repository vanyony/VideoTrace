package com.videotrace.dto;

public enum TaskStage {
    QUEUED,
    CONSUMING,
    VIDEO_CONTEXT,
    CONTEXT_COMPLETED,
    CHUNKS_COMPLETED,
    RETRIEVAL,
    RETRYING,
    COMPLETED,
    COMPLETED_REUSED,
    FAILED,
    DEAD_LETTERED,
    MANUAL_REPLAY,
    TRANSCRIPTION,
    ASR,
    DISPATCH_FAILED;

    public static TaskStage from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
