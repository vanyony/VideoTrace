package com.videotrace.entity;

public record UploadPart(int partIndex, long sizeBytes, String checksum) {}
