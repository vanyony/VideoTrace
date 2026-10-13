package com.videotrace.entity;

public record UploadSession(String uploadId, Long userId, String filename, int totalChunks,
                            Long expectedBytes, String state, Long mediaId, boolean expired) {}
