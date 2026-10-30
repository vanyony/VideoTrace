package com.videotrace.dto;

import java.io.Serializable;

public class ProcessingTaskMsg implements Serializable {

    public static final String PREPARE_SOURCE = "PREPARE_SOURCE";
    public static final String PREPARATION_GOAL = "准备创作素材";
    public static final String TRANSCRIBE = "TRANSCRIBE";
    public static final String TRANSCRIPTION_GOAL = "提取视频文字";

    private String taskId;
    private Long mediaId;
    private String action;
    private String contentHash;
    private String userGoal;
    /** 持久任务兼容范围(见 {@link ProcessingScope})。可空,历史消息或缺省时按 GENERAL 处理。 */
    private String mode;

    public ProcessingTaskMsg() {}

    /** 兼容旧调用方的四参构造器,模式默认 GENERAL。 */
    public ProcessingTaskMsg(Long mediaId, String action, String contentHash, String userGoal) {
        this(mediaId, action, contentHash, userGoal, ProcessingScope.GENERAL.name());
    }

    public ProcessingTaskMsg(Long mediaId, String action, String contentHash, String userGoal, String mode) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
        this.userGoal = userGoal;
        this.mode = mode;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPreparation() { return PREPARE_SOURCE.equals(action); }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isTranscription() { return TRANSCRIBE.equals(action); }

    public boolean hasSupportedAction() {
        return isSupportedAction(action);
    }

    /** 供失败台账等场景在没有消息实例时复用同一套 action 判定，避免两处规则漂移。 */
    public static boolean isSupportedAction(String action) {
        return PREPARE_SOURCE.equals(action) || TRANSCRIBE.equals(action);
    }
}
