package com.videotrace.dto;

/** Default metadata scope for durable source processing. */
public enum ProcessingScope {
    GENERAL;
    public static ProcessingScope fromNullable(String value) { return GENERAL; }
    public static ProcessingScope fromRequest(String value) { return GENERAL; }
}
