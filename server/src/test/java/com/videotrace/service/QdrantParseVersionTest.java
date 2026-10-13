package com.videotrace.service;

import com.videotrace.dto.VideoChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class QdrantParseVersionTest {
    @Test void indexesAndQueriesOnlyCurrentVersionButDeletesAllVersionsOfMedia() throws Exception {
        ObjectMapper json=new ObjectMapper();
        List<String> writes=new CopyOnWriteArrayList<>();
        List<String> queries=new CopyOnWriteArrayList<>();
        List<String> deletes=new CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String path=exchange.getRequestURI().getPath();
            if (path.endsWith("/points/query")) queries.add(body);
            else if (path.endsWith("/points/delete")) deletes.add(body);
            else if (path.endsWith("/points")) writes.add(body);
            byte[] result="{\"result\":{\"points\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,result.length);
            exchange.getResponseBody().write(result); exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            QdrantVectorStore first=new QdrantVectorStore(true,url,"","videos","v1");
            QdrantVectorStore second=new QdrantVectorStore(true,url,"","videos","v2");
            VideoChunk chunk=new VideoChunk(0,60000,"summary",List.of(),List.of(),List.of(1.0,0.0));
            first.upsert(7L,List.of(chunk));
            second.upsert(7L,List.of(chunk));
            assertNotEquals(json.readTree(writes.get(0)).at("/points/0/id").asText(),
                            json.readTree(writes.get(1)).at("/points/0/id").asText());
            assertEquals("v2",json.readTree(writes.get(1)).at("/points/0/payload/parseVersion").asText());
            second.search(7L,List.of(1.0,0.0),3);
            assertEquals("parseVersion",json.readTree(queries.getFirst()).at("/filter/must/1/key").asText());
            assertEquals("v2",json.readTree(queries.getFirst()).at("/filter/must/1/match/value").asText());
            second.deleteMedia(7L);
            assertEquals(1,json.readTree(deletes.getFirst()).at("/filter/must").size());
            assertEquals("mediaId",json.readTree(deletes.getFirst()).at("/filter/must/0/key").asText());
        } finally { server.stop(0); }
    }
}
