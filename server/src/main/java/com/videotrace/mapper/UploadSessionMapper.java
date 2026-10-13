package com.videotrace.mapper;

import com.videotrace.entity.UploadSession;
import com.videotrace.entity.UploadPart;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface UploadSessionMapper {
    String COLUMNS = "upload_id AS uploadId,user_id AS userId,filename,total_chunks AS totalChunks,"
            + "expected_bytes AS expectedBytes,state,media_id AS mediaId,expires_at<=CURRENT_TIMESTAMP AS expired";

    @Insert("INSERT INTO upload_sessions(upload_id,user_id,filename,total_chunks,expected_bytes,expires_at) "
            + "VALUES(#{id},#{user},#{filename},#{chunks},#{bytes},DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 24 HOUR))")
    int insert(@Param("id") String id, @Param("user") Long user, @Param("filename") String filename,
               @Param("chunks") int chunks, @Param("bytes") Long bytes);

    @Select("SELECT " + COLUMNS + " FROM upload_sessions WHERE upload_id=#{id}")
    UploadSession find(String id);

    @Select("SELECT " + COLUMNS + " FROM upload_sessions WHERE upload_id=#{id} FOR UPDATE")
    UploadSession lock(String id);

    @Update("UPDATE upload_sessions SET expires_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 24 HOUR) "
            + "WHERE upload_id=#{id} AND state='OPEN' AND expires_at>CURRENT_TIMESTAMP")
    int touch(String id);

    @Select("SELECT part_index AS partIndex,size_bytes AS sizeBytes,checksum FROM upload_parts "
            + "WHERE upload_id=#{id} ORDER BY part_index")
    List<UploadPart> parts(String id);

    @Insert("INSERT INTO upload_parts(upload_id,part_index,size_bytes,checksum) "
            + "SELECT upload_id,#{part},#{bytes},#{checksum} FROM upload_sessions "
            + "WHERE upload_id=#{id} AND state='OPEN' AND expires_at>CURRENT_TIMESTAMP")
    int insertPart(@Param("id") String id, @Param("part") int part,
                   @Param("bytes") long bytes, @Param("checksum") String checksum);

    @Delete("DELETE FROM upload_parts WHERE upload_id=#{id} AND part_index=#{part}")
    int forgetPart(@Param("id") String id, @Param("part") int part);

    @Update("UPDATE upload_sessions SET state='COMPLETED',media_id=#{media},cleanup_pending=TRUE,"
            + "cleanup_after=CURRENT_TIMESTAMP WHERE upload_id=#{id} AND state='OPEN'")
    int complete(@Param("id") String id, @Param("media") Long media);

    @Select("SELECT " + COLUMNS + " FROM upload_sessions WHERE cleanup_pending=TRUE "
            + "AND cleanup_after<=CURRENT_TIMESTAMP AND (state IN ('COMPLETED','EXPIRED') "
            + "OR expires_at<=CURRENT_TIMESTAMP) ORDER BY cleanup_after LIMIT 10")
    List<UploadSession> cleanupCandidates();

    @Update("UPDATE upload_sessions SET state='EXPIRED' WHERE upload_id=#{id} "
            + "AND state='OPEN' AND expires_at<=CURRENT_TIMESTAMP")
    int expire(String id);

    @Update("UPDATE upload_sessions SET cleanup_pending=FALSE WHERE upload_id=#{id} AND state<>'OPEN'")
    int cleaned(String id);

    @Update("UPDATE upload_sessions SET cleanup_after=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 60 SECOND) WHERE upload_id=#{id}")
    int retryCleanup(String id);

    @Delete("DELETE FROM upload_parts WHERE upload_id=#{id}")
    int clearParts(String id);
}
