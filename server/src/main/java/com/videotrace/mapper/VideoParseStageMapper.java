package com.videotrace.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface VideoParseStageMapper {
    @Select("""
        SELECT payload FROM video_parse_stages
        WHERE media_id=#{mediaId} AND parse_version=#{version} AND branch=#{branch} AND state='SUCCEEDED'
        """)
    String completed(@Param("mediaId") Long mediaId,@Param("version") String version,@Param("branch") String branch);

    @Insert("""
        INSERT INTO video_parse_stages(media_id,parse_version,branch,state,payload)
        SELECT id,#{version},#{branch},'SUCCEEDED',#{payload} FROM media_files WHERE id=#{mediaId}
        ON DUPLICATE KEY UPDATE payload=IF(state='SUCCEEDED',payload,VALUES(payload)),
          last_error=NULL,state='SUCCEEDED'
        """)
    int success(@Param("mediaId") Long mediaId,@Param("version") String version,
                @Param("branch") String branch,@Param("payload") String payload);

    @Insert("""
        INSERT INTO video_parse_stages(media_id,parse_version,branch,state,last_error)
        SELECT id,#{version},#{branch},'FAILED',#{error} FROM media_files WHERE id=#{mediaId}
        ON DUPLICATE KEY UPDATE last_error=IF(state='SUCCEEDED',last_error,VALUES(last_error))
        """)
    int failure(@Param("mediaId") Long mediaId,@Param("version") String version,
                @Param("branch") String branch,@Param("error") String error);

    @Select("SELECT payload FROM video_parse_stages WHERE media_id=#{mediaId} AND branch='OCR' AND state='SUCCEEDED'")
    List<String> frames(@Param("mediaId") Long mediaId);

    @Delete("DELETE FROM video_parse_stages WHERE media_id=#{mediaId}")
    int deleteMedia(@Param("mediaId") Long mediaId);
}
