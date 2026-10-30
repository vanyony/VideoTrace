package com.videotrace.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videotrace.service.ExternalCallLimiter;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextModelClientTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> request=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private int status=200,failures=0;
    private String finish="stop",content="{\"semanticQuery\":\"原视频主题\",\"keywords\":[\"主题\"],\"visualKeywords\":[]}";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            int count=calls.incrementAndGet();
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var envelope=json.createObjectNode();
            envelope.putArray("choices").addObject().put("finish_reason",finish).putObject("message").put("content",content);
            byte[] bytes=json.writeValueAsBytes(envelope);
            exchange.sendResponseHeaders(count<=failures?503:status,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private TextModelClient client() {
        var limiter=mock(ExternalCallLimiter.class);
        when(limiter.acquire("llm")).thenAnswer(invocation -> mock(ExternalCallLimiter.Permit.class));
        return new TextModelClient("test-secret","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","test-model",2,json,limiter);
    }
    @Test void sendsAuthenticatedChatAndParsesStructuredContent() throws Exception {
        assertEquals("原视频主题",client().planRetrieval("找主题").semanticQuery());
        assertEquals("Bearer test-secret",authorization.get());
        var payload=json.readTree(request.get());
        assertEquals("test-model",payload.path("model").asText());
        assertEquals("system",payload.path("messages").path(0).path("role").asText());
        assertFalse(payload.has("tools"));
    }
    @Test void retriesExplicitTransientResponsesButNotAuthenticationFailures() {
        failures=1;
        assertEquals("原视频主题",client().planRetrieval("query").semanticQuery());
        assertEquals(2,calls.get());
        calls.set(0); failures=0; status=401;
        var error=assertThrows(IllegalStateException.class,()->client().planRetrieval("query"));
        assertEquals(1,calls.get());
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("test-secret"));
    }
    @Test void rejectsTruncatedAndMalformedResponsesWithoutRetry() {
        finish="length";
        assertThrows(IllegalStateException.class,()->client().planRetrieval("query"));
        assertEquals(1,calls.get());
        finish="stop"; content="not JSON"; calls.set(0);
        assertThrows(IllegalStateException.class,()->client().planRetrieval("query"));
        assertEquals(1,calls.get());
    }
}
