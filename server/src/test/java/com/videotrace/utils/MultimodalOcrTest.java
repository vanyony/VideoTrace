package com.videotrace.utils;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;
import com.videotrace.service.ExternalCallLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultimodalOcrTest {
    @TempDir Path directory;

    @Test void sendsImagesWithIndependentCredentialsAndRejectsPartialResults() throws Exception {
        var finish=new AtomicReference<>("stop");
        var content=new AtomicReference<>("画面原文");
        var limiter=mock(ExternalCallLimiter.class);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", exchange -> {
            assertEquals("Bearer ocr-test-key",exchange.getRequestHeaders().getFirst("Authorization"));
            var body=JSON.parseObject(exchange.getRequestBody().readAllBytes());
            assertEquals("configured-vision-model",body.getString("model"));
            String image=body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
                    .getJSONObject("image_url").getString("url");
            assertTrue(image.startsWith("data:image/png;base64,"));
            byte[] response=JSON.toJSONBytes(java.util.Map.of("choices",java.util.List.of(java.util.Map.of(
                    "finish_reason",finish.get(),"message",java.util.Map.of("content",content.get())))));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            Path image=directory.resolve("frame.png");Files.write(image,new byte[]{1,2,3});
            var ocr=new OcrUtils("missing-local-command","multimodal","ocr-test-key",
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions","configured-vision-model",limiter);
            assertEquals("画面原文",ocr.recognize(image.toFile()));
            content.set("");assertEquals("",ocr.recognize(image.toFile()));
            finish.set("length");assertThrows(IllegalStateException.class,()->ocr.recognize(image.toFile()));
            verify(limiter,times(3)).acquire("llm");
            var missing=new OcrUtils("unused","multimodal","","http://localhost","vision",limiter);
            assertThrows(IllegalStateException.class,()->missing.recognize(image.toFile()));
        } finally {server.stop(0);}
    }
}
