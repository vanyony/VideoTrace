package com.videotrace.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Base64;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import com.videotrace.service.ExternalCallLimiter;

@Component
public class OcrUtils {

    private static final Logger log = LoggerFactory.getLogger(OcrUtils.class);

    private final String ocrCommand;
    private final String provider, apiKey, apiUrl, model;
    private final ExternalCallLimiter limiter;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();

    public OcrUtils(@Value("${tool.ocr.command:tesseract}") String ocrCommand,
                    @Value("${ai.ocr.provider:local}") String provider,
                    @Value("${ai.ocr.api-key:}") String apiKey,
                    @Value("${ai.ocr.url:https://api.siliconflow.cn/v1/chat/completions}") String apiUrl,
                    @Value("${ai.ocr.model:zai-org/GLM-5.3-Flash}") String model,
                    ExternalCallLimiter limiter) {
        this.ocrCommand = ocrCommand;
        if (!provider.equals("local") && !provider.equals("multimodal")) throw new IllegalArgumentException("invalid OCR_PROVIDER");
        this.provider=provider; this.apiKey=apiKey; this.apiUrl=apiUrl; this.model=model; this.limiter=limiter;
    }

    public String recognize(File image) {
        if (image == null || !image.isFile()) throw new IllegalArgumentException("OCR image does not exist");
        if (provider.equals("multimodal")) return recognizeRemote(image);
        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile("videotrace-ocr-", ".txt");
            process = new ProcessBuilder(
                    ocrCommand, image.getAbsolutePath(), "stdout", "-l", "chi_sim+eng")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR execution timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("OCR process failed with exit code " + process.exitValue());
            }
            return Files.readString(output, StandardCharsets.UTF_8).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR execution interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR failed for " + image.getName(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception e) {
                    log.warn("ocr_output_cleanup_failed path={}", output, e);
                }
            }
        }
    }

    private String recognizeRemote(File image) {
        if (apiKey.isBlank()) throw new IllegalStateException("OCR API key is not configured");
        if (image.length() > 10L * 1024 * 1024) throw new IllegalArgumentException("OCR frame exceeds 10 MiB");
        try (var permit = limiter.acquire("llm")) {
            String mime = Files.probeContentType(image.toPath());
            if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) throw new IllegalArgumentException("OCR requires JPEG or PNG");
            JSONObject text=JSONObject.of("type","text","text","只抄录图片中清晰可见的文字，按阅读顺序保留换行。不要描述画面，不要猜测或补写看不清的文字。没有可识别文字时返回空字符串。");
            JSONObject picture=JSONObject.of("type","image_url","image_url",JSONObject.of("url",
                    "data:"+mime+";base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(image.toPath()))));
            JSONObject payload=JSONObject.of("model",model,"messages",java.util.List.of(JSONObject.of("role","user","content",java.util.List.of(picture,text))),
                    "temperature",0,"enable_thinking",false,"max_tokens",2048);
            Request request=new Request.Builder().url(apiUrl).header("Authorization","Bearer "+apiKey)
                    .post(RequestBody.create(payload.toJSONString(),MediaType.parse("application/json"))).build();
            try (Response response=client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IllegalStateException("OCR API HTTP "+response.code());
                if(response.body()==null) throw new IllegalStateException("OCR API response is empty");
                byte[] bytes=response.body().byteStream().readNBytes(128001);
                if(bytes.length>128000) throw new IllegalStateException("OCR API response too large");
                JSONObject choice=JSON.parseObject(bytes).getJSONArray("choices").getJSONObject(0);
                if(!"stop".equals(choice.getString("finish_reason"))) throw new IllegalStateException("OCR response is incomplete");
                Object content=choice.getJSONObject("message").get("content");
                if(!(content instanceof String result) || result.length()>16000) throw new IllegalStateException("OCR text is invalid or too large");
                return result.trim();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("OCR API transport failed", e);
        }
    }
}
