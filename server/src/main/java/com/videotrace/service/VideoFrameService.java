package com.videotrace.service;

import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class VideoFrameService {
    private final ExternalCallLimiter limiter;
    public VideoFrameService(ExternalCallLimiter limiter) { this.limiter=limiter; }

    public Map<String,Object> extract(String source,long timestampMs) {
        Path output=null;
        Process process=null;
        try (var permit=limiter.acquire("media")) {
            output=Files.createTempFile("videotrace-inspect-", ".jpg");
            process=new ProcessBuilder("ffmpeg","-y","-ss",String.format(Locale.ROOT,"%.3f",timestampMs/1000.0),
                    "-i",source,"-frames:v","1","-vf","scale=960:540:force_original_aspect_ratio=decrease",output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(20,TimeUnit.SECONDS)) throw new IllegalStateException("frame extraction timed out");
            if(process.exitValue()!=0 || Files.size(output)==0 || Files.size(output)>1024*1024)
                throw new IllegalArgumentException("video frame is unavailable or too large");
            byte[] bytes=Files.readAllBytes(output);
            return Map.of("timestampMs",timestampMs,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "imageDataUrl","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes));
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("frame extraction interrupted",e);
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("frame extraction failed",e);
        } finally {
            if(process!=null && process.isAlive()) process.destroyForcibly();
            if(output!=null) try { Files.deleteIfExists(output); } catch(java.io.IOException ignored) { }
        }
    }
}
