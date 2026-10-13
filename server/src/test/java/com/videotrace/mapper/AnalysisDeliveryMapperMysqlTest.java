package com.videotrace.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL check. Only a connection-local temporary table is created. */
@EnabledIfEnvironmentVariable(named="VIDEOTRACE_TEST_JDBC_URL",matches=".+")
class AnalysisDeliveryMapperMysqlTest {
    @Test
    void sqlClaimsExpiredLeaseAndPreservesConsumerObservation() throws Exception {
        UnpooledDataSource data=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv("VIDEOTRACE_TEST_JDBC_URL"),
                System.getenv("VIDEOTRACE_TEST_DB_USER"),
                System.getenv("VIDEOTRACE_TEST_DB_PASSWORD"));
        Configuration config=new Configuration(new Environment("test",new JdbcTransactionFactory(),data));
        config.addMapper(AnalysisDeliveryMapper.class);
        config.addMapper(AnalysisExecutionMapper.class);
        config.addMapper(VideoParseStageMapper.class);
        config.addMapper(UploadSessionMapper.class);
        try (var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            Connection connection=session.getConnection();
            String ddl;
            try (var resource=getClass().getResourceAsStream("/db/migration/V4__create_analysis_deliveries.sql")) {
                assertNotNull(resource);
                ddl=new String(resource.readAllBytes(),StandardCharsets.UTF_8)
                        .replace("CREATE TABLE analysis_deliveries","CREATE TEMPORARY TABLE analysis_deliveries");
            }
            try (var statement=connection.createStatement()) { statement.execute(ddl); }
            try (var resource=getClass().getResourceAsStream("/db/migration/V5__create_analysis_executions.sql")) {
                assertNotNull(resource);
                String executionDdl=new String(resource.readAllBytes(),StandardCharsets.UTF_8)
                        .split("-- Existing observed")[0]
                        .replace("CREATE TABLE analysis_executions","CREATE TEMPORARY TABLE analysis_executions");
                try (var statement=connection.createStatement()) { statement.execute(executionDdl); }
            }
            try (var statement=connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE media_files(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO media_files VALUES(7)");
            }
            try (var resource=getClass().getResourceAsStream("/db/migration/V6__create_video_parse_stages.sql")) {
                assertNotNull(resource);
                String stageDdl=new String(resource.readAllBytes(),StandardCharsets.UTF_8)
                    .replace("CREATE TABLE video_parse_stages","CREATE TEMPORARY TABLE video_parse_stages");
                try (var statement=connection.createStatement()) { statement.execute(stageDdl); }
            }
            try (var statement=connection.createStatement()) {
                statement.execute("ALTER TABLE analysis_executions ADD COLUMN next_run_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
            }
            AnalysisDeliveryMapper mapper=session.getMapper(AnalysisDeliveryMapper.class);
            String id=UUID.randomUUID().toString();
            String digest="a".repeat(64);
            assertEquals(1,mapper.insert(id,1L,7L,digest,"7:"+digest,"{}"));
            assertEquals(1,mapper.pending(7L,digest));
            assertTrue(mapper.due(50).contains(id));
            assertEquals(1,mapper.claim(id,"first",60));
            assertEquals(0,mapper.claim(id,"second",60));
            try (var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE analysis_deliveries SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
            }
            assertEquals(1,mapper.claim(id,"second",60));
            assertEquals(0,mapper.sent(id,"first",300));
            assertEquals(1,mapper.observed(id));
            assertEquals(0,mapper.sent(id,"second",300));
            assertEquals(0,mapper.retry(id,"second",5,"failure"));
            assertEquals(0,mapper.pending(7L,digest));
            assertFalse(mapper.due(50).contains(id));
            AnalysisExecutionMapper execution=session.getMapper(AnalysisExecutionMapper.class);
            assertEquals(1,execution.insert(id,1L,7L,digest,"7:"+digest));
            assertEquals(1,execution.claim(id,"old-worker",120));
            assertEquals(0,execution.claim(id,"duplicate-worker",120));
            assertEquals(id,execution.lockOwner(id,"old-worker"));
            assertEquals(1,execution.heartbeat(id,"old-worker",120));
            try (var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE analysis_executions SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
            }
            assertEquals(0,execution.heartbeat(id,"old-worker",120));
            assertEquals(1,execution.recover(id));
            assertEquals(1,execution.claim(id,"new-worker",120));
            assertNull(execution.lockOwner(id,"old-worker"));
            assertEquals(0,execution.finish(id,"old-worker","SUCCEEDED",null));
            assertEquals(1,execution.finish(id,"new-worker","SUCCEEDED",null));
            assertEquals("SUCCEEDED",execution.find(id).state());
            assertEquals(0,execution.claim(id,"redelivery",120));

            String busyId=UUID.randomUUID().toString();
            execution.insert(busyId,1L,7L,"b".repeat(64),"7:busy");
            for(int attempt=0;attempt<5;attempt++) {
                assertEquals(1,execution.claim(busyId,"busy-worker",120));
                assertEquals(1,execution.defer(busyId,"busy-worker",30));
                assertEquals(0,execution.find(busyId).attempts());
                assertEquals(0,execution.claim(busyId,"too-early",120));
                try(var statement=connection.createStatement()) {
                    statement.executeUpdate("UPDATE analysis_executions SET next_run_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
                }
            }

            VideoParseStageMapper stages=session.getMapper(VideoParseStageMapper.class);
            stages.success(7L,"v1","OCR","[]");
            stages.failure(7L,"v1","OCR","timeout");
            stages.success(7L,"v1","OCR","[1]");
            assertEquals("[]",stages.completed(7L,"v1","OCR"));
            stages.success(7L,"v2","OCR","[]");
            assertEquals(2,stages.frames(7L).size());
            stages.success(999L,"v1","ASR","[]");
            assertNull(stages.completed(999L,"v1","ASR"));
            stages.deleteMedia(7L);
            assertTrue(stages.frames(7L).isEmpty());

            try (var resource=getClass().getResourceAsStream("/db/migration/V8__durable_upload_sessions.sql")) {
                assertNotNull(resource);
                String uploadDdl=new String(resource.readAllBytes(),StandardCharsets.UTF_8)
                        .replace("CREATE TABLE upload_sessions","CREATE TEMPORARY TABLE upload_sessions")
                        .replace("CREATE TABLE upload_parts","CREATE TEMPORARY TABLE upload_parts");
                try (var statement=connection.createStatement()) {
                    for (String sql:uploadDdl.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
            UploadSessionMapper uploads=session.getMapper(UploadSessionMapper.class);
            String uploadId=UUID.randomUUID().toString();
            assertEquals(1,uploads.insert(uploadId,1L,"sample.mp4",1,3L));
            var upload=uploads.find(uploadId);
            assertEquals(3L,upload.expectedBytes());
            assertFalse(upload.expired());
            assertEquals("OPEN",upload.state());
            assertEquals(1,uploads.insertPart(uploadId,0,3,"c".repeat(64)));
            assertEquals(0,uploads.parts(uploadId).getFirst().partIndex());
            assertEquals(1,uploads.touch(uploadId));
            assertNotNull(uploads.lock(uploadId));
            try (var statement=connection.createStatement()) {
                statement.execute("INSERT INTO media_files(id,source_upload_id) VALUES(8,'" + uploadId + "')");
                assertThrows(java.sql.SQLException.class,() -> statement.execute(
                        "INSERT INTO media_files(id,source_upload_id) VALUES(9,'" + uploadId + "')"));
            }
            assertEquals(1,uploads.complete(uploadId,8L));
            assertEquals(0,uploads.complete(uploadId,9L));
            assertEquals(0,uploads.insertPart(uploadId,1,3,"d".repeat(64)));
            assertEquals(8L,uploads.find(uploadId).mediaId());
            assertEquals(1,uploads.clearParts(uploadId));
            assertEquals(1,uploads.cleaned(uploadId));
            assertTrue(uploads.parts(uploadId).isEmpty());
            assertFalse(uploads.cleanupCandidates().stream().anyMatch(u -> u.uploadId().equals(uploadId)));
            String expiredId=UUID.randomUUID().toString();
            uploads.insert(expiredId,1L,"sample.mp4",1,null);
            try (var statement=connection.createStatement()) {
                statement.execute("UPDATE upload_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)"
                        + " WHERE upload_id='" + expiredId + "'");
            }
            session.clearCache();
            assertTrue(uploads.find(expiredId).expired());
            assertEquals(0,uploads.touch(expiredId));
            assertEquals(1,uploads.expire(expiredId));
            assertEquals("EXPIRED",uploads.find(expiredId).state());

            // Observation releases pending uniqueness, while both rows retain their audit history.
            assertEquals(1,mapper.insert(UUID.randomUUID().toString(),1L,7L,digest,"7:"+digest,"{}"));
        }
    }
}
