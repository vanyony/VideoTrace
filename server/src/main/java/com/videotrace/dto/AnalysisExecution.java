package com.videotrace.dto;

public record AnalysisExecution(String taskId, Long userId, Long mediaId, String state,
                                int attempts, String lastError) {}
