package com.videotrace.dto;

public record ProcessingExecution(String taskId, Long userId, Long mediaId, String state,
                                int attempts, String lastError) {}
