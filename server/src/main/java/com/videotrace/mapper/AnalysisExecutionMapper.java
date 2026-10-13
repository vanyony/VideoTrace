package com.videotrace.mapper;

import com.videotrace.dto.AnalysisExecution;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AnalysisExecutionMapper {
    @Insert("""
        INSERT INTO analysis_executions(task_id,user_id,media_id,goal_digest,active_scope)
        VALUES(#{id},#{userId},#{mediaId},#{digest},#{scope})
        """)
    int insert(@Param("id") String id,@Param("userId") Long userId,@Param("mediaId") Long mediaId,
               @Param("digest") String digest,@Param("scope") String scope);

    @Insert("""
        INSERT INTO analysis_executions(task_id,user_id,media_id,goal_digest,active_scope,task_kind)
        VALUES(#{id},#{userId},#{mediaId},#{digest},#{scope},'TRANSCRIPTION')
        """)
    int insertTranscription(@Param("id") String id,@Param("userId") Long userId,@Param("mediaId") Long mediaId,
               @Param("digest") String digest,@Param("scope") String scope);

    @Update("""
        UPDATE analysis_executions SET state='RUNNING',execution_token=#{token},
          attempts=attempts+1,lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND state='QUEUED' AND attempts<3 AND next_run_at<=CURRENT_TIMESTAMP(3)
        """)
    int claim(@Param("id") String id,@Param("token") String token,@Param("seconds") int seconds);

    @Update("""
        UPDATE analysis_executions SET lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND state='RUNNING' AND execution_token=#{token}
          AND lease_until>CURRENT_TIMESTAMP(3)
        """)
    int heartbeat(@Param("id") String id,@Param("token") String token,@Param("seconds") int seconds);

    @Select("""
        SELECT e.task_id FROM analysis_executions e JOIN media_files m ON m.id=e.media_id
        WHERE e.task_id=#{id} AND e.state='RUNNING' AND e.execution_token=#{token}
          AND e.lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE
        """)
    String lockOwner(@Param("id") String id,@Param("token") String token);

    @Update("""
        UPDATE analysis_executions SET state=#{state},last_error=#{error},execution_token=NULL,
          lease_until=NULL,active_scope=IF(#{state}='QUEUED',active_scope,NULL)
        WHERE task_id=#{id} AND state='RUNNING' AND execution_token=#{token}
          AND lease_until>CURRENT_TIMESTAMP(3)
        """)
    int finish(@Param("id") String id,@Param("token") String token,
               @Param("state") String state,@Param("error") String error);

    @Update("""
        UPDATE analysis_executions SET state='QUEUED',attempts=GREATEST(0,attempts-1),
          execution_token=NULL,lease_until=NULL,last_error='ResourceBusy',
          next_run_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND state='RUNNING' AND execution_token=#{token}
          AND lease_until>CURRENT_TIMESTAMP(3)
        """)
    int defer(@Param("id") String id,@Param("token") String token,@Param("delay") int delay);

    @Select("""
        SELECT task_id FROM analysis_executions
        WHERE state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(3)
        ORDER BY lease_until LIMIT 50
        """)
    List<String> expired();

    @Update("""
        UPDATE analysis_executions SET state=IF(attempts>=3,'FAILED','QUEUED'),
          last_error='WorkerLeaseExpired',execution_token=NULL,lease_until=NULL,
          active_scope=IF(attempts>=3,NULL,active_scope)
        WHERE task_id=#{id} AND state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(3)
        """)
    int recover(@Param("id") String id);

    @Select("""
        SELECT task_id AS taskId,user_id AS userId,media_id AS mediaId,state,attempts,last_error AS lastError
        FROM analysis_executions WHERE task_id=#{id}
        """)
    AnalysisExecution find(@Param("id") String id);

    @Select("""
        SELECT task_id AS taskId,user_id AS userId,media_id AS mediaId,state,attempts,last_error AS lastError
        FROM analysis_executions WHERE media_id=#{mediaId} AND goal_digest=#{digest} AND task_kind='ANALYSIS'
        ORDER BY creation_order DESC LIMIT 1
        """)
    AnalysisExecution latest(@Param("mediaId") Long mediaId,@Param("digest") String digest);

    @Select("""
        SELECT task_id AS taskId,user_id AS userId,media_id AS mediaId,state,attempts,last_error AS lastError
        FROM analysis_executions WHERE media_id=#{mediaId} AND task_kind='TRANSCRIPTION'
        ORDER BY creation_order DESC LIMIT 1
        """)
    AnalysisExecution latestTranscription(@Param("mediaId") Long mediaId);
}
