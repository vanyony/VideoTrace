package com.videotrace.utils;

import com.sun.net.httpserver.HttpServer;
import com.videotrace.service.ExternalCallLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsrCallQuotaTest {
    @TempDir Path directory;
    @Test void everyRealRetryPassesThroughExecutionQuota() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt=calls.incrementAndGet();
            byte[] body="{\"text\":\"测试转写\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt==1?500:200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Path file=directory.resolve("audio.mp3"); Files.write(file,new byte[]{1,2,3});
            ExternalCallLimiter limiter=mock(ExternalCallLimiter.class);
            AliyunAsrUtils asr=new AliyunAsrUtils("test-key","http://127.0.0.1:"+server.getAddress().getPort(),"test-model",limiter);
            assertEquals("测试转写",asr.audioToText(file.toString()));
            assertEquals(2,calls.get());
            verify(limiter,times(2)).acquire("asr");
        } finally { server.stop(0); }
    }
}
