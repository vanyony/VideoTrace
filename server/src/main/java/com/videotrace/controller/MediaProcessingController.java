package com.videotrace.controller;

import com.videotrace.common.ErrorCode;
import com.videotrace.common.Result;
import com.videotrace.dto.ProcessingScope;
import com.videotrace.dto.TaskEvent;
import com.videotrace.dto.TaskStatus;
import com.videotrace.dto.TaskStage;
import com.videotrace.entity.MediaFile;
import com.videotrace.exception.BusinessException;
import com.videotrace.service.AudioExportService;
import com.videotrace.service.AuthService;
import com.videotrace.service.MediaService;
import com.videotrace.service.TaskEventService;
import com.videotrace.service.TranscriptionTaskService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/media-processing")
public class MediaProcessingController {

    private final AudioExportService audioExportService;
    private final MediaService mediaService;
    private final TranscriptionTaskService transcriptionTaskService;
    private final TaskEventService taskEventService;

    public MediaProcessingController(AudioExportService audioExportService,
                                     MediaService mediaService,
                                     TranscriptionTaskService transcriptionTaskService,
                                     TaskEventService taskEventService) {
        this.audioExportService = audioExportService;
        this.mediaService = mediaService;
        this.transcriptionTaskService = transcriptionTaskService;
        this.taskEventService = taskEventService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<Result<Void>> transcribe(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        if (!transcriptionTaskService.submit(mediaFile)) {
            throw new BusinessException(ErrorCode.CONFLICT, "文字提取任务正在处理中");
        }
        // 异步受理：202 Accepted，结果由 transcription-status / SSE 获取。
        return ResponseEntity.accepted().body(Result.ok());
    }

    @GetMapping("/transcription-status")
    public Result<TaskStatus> transcriptionStatus(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        return Result.ok(transcriptionTaskService.status(mediaFile));
    }

    @GetMapping(value = "/transcription-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter transcriptionEvents(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.requireOwnedMedia(id, userId);
        return taskEventService.subscribe(
                id,
                TaskEventService.TRANSCRIPTION,
                "",
                ProcessingScope.GENERAL,
                () -> TaskEvent.of(transcriptionTaskService.status(mediaService.requireOwnedMedia(id, userId)),
                        TaskStage.TRANSCRIPTION));
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        Path outputPath = audioExportService.exportMp3(mediaFile);

        StreamingResponseBody body = output -> {
            try {
                Files.copy(outputPath, output);
            } finally {
                Files.deleteIfExists(outputPath);
            }
        };
        String filename = mediaFile.getFilename() == null
                ? "audio.mp3"
                : mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(body);
    }
}
