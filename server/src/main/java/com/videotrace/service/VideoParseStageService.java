package com.videotrace.service;

import com.videotrace.mapper.VideoParseStageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VideoParseStageService {
    private final VideoParseStageMapper mapper;
    private final ObjectMapper json;
    private final String version;

    public VideoParseStageService(VideoParseStageMapper mapper,ObjectMapper json,
            @Value("${video.parse.version:v1}") String version) {
        if (!version.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid video parse version");
        this.mapper=mapper; this.json=json; this.version=version;
    }
    public String version() { return version; }
    public <T> List<T> load(Long mediaId,String branch,TypeReference<List<T>> type) {
        String payload=mapper.completed(mediaId,version,branch);
        return payload==null?null:decode(payload,type);
    }
    public boolean saveSuccess(Long mediaId,String branch,List<?> items) {
        try {
            String payload=json.writeValueAsString(items);
            AtomicBoolean saved=new AtomicBoolean();
            TaskExecutionContext.guarded(() -> {
                mapper.success(mediaId,version,branch,payload);
                String persisted=mapper.completed(mediaId,version,branch);
                if (persisted==null) throw new IllegalArgumentException("Video no longer exists");
                saved.set(payload.equals(persisted));
            });
            return saved.get();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize parse result",e);
        }
    }
    public void failure(Long mediaId,String branch,Exception error) {
        TaskExecutionContext.guarded(() -> mapper.failure(mediaId,version,branch,error.getClass().getSimpleName()));
    }
    public List<VideoContextService.FramePart> allFrames(Long mediaId) {
        List<VideoContextService.FramePart> frames=new ArrayList<>();
        for (String payload:mapper.frames(mediaId))
            frames.addAll(decode(payload,new TypeReference<List<VideoContextService.FramePart>>() {}));
        return frames;
    }
    public void deleteMedia(Long mediaId) { mapper.deleteMedia(mediaId); }
    private <T> List<T> decode(String payload,TypeReference<List<T>> type) {
        try { return json.readValue(payload,type); }
        catch (Exception e) { throw new IllegalStateException("Invalid parse checkpoint",e); }
    }
}
