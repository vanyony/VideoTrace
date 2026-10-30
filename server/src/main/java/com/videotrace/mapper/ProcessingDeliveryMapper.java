package com.videotrace.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ProcessingDeliveryMapper {
    @Insert("""
        INSERT INTO analysis_deliveries(task_id,user_id,media_id,goal_digest,active_scope,payload)
        VALUES(#{id},#{userId},#{mediaId},#{digest},#{scope},#{payload})
        """)
    int insert(@Param("id") String id, @Param("userId") Long userId,
               @Param("mediaId") Long mediaId, @Param("digest") String digest,
               @Param("scope") String scope, @Param("payload") String payload);

    @Select("""
        SELECT task_id FROM analysis_deliveries
        WHERE status IN ('PENDING','SENDING','SENT') AND next_attempt_at<=CURRENT_TIMESTAMP(3)
        ORDER BY next_attempt_at LIMIT #{batch}
        """)
    List<String> due(@Param("batch") int batch);

    @Update("""
        UPDATE analysis_deliveries SET status='SENDING',lease_token=#{token},attempts=attempts+1,
            next_attempt_at=TIMESTAMPADD(SECOND,#{lease},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND status IN ('PENDING','SENDING','SENT')
            AND next_attempt_at<=CURRENT_TIMESTAMP(3)
        """)
    int claim(@Param("id") String id, @Param("token") String token, @Param("lease") int lease);

    @Select("SELECT payload FROM analysis_deliveries WHERE task_id=#{id}")
    String payload(@Param("id") String id);

    @Select("SELECT attempts FROM analysis_deliveries WHERE task_id=#{id}")
    int attempts(@Param("id") String id);

    @Update("""
        UPDATE analysis_deliveries SET status='SENT',last_error=NULL,lease_token=NULL,
            next_attempt_at=TIMESTAMPADD(SECOND,#{ack},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND status='SENDING' AND lease_token=#{token}
        """)
    int sent(@Param("id") String id, @Param("token") String token, @Param("ack") int ack);

    @Update("""
        UPDATE analysis_deliveries SET status='PENDING',last_error=#{error},lease_token=NULL,
            next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id} AND status='SENDING' AND lease_token=#{token}
        """)
    int retry(@Param("id") String id, @Param("token") String token,
              @Param("delay") int delay, @Param("error") String error);

    @Update("""
        UPDATE analysis_deliveries SET status='OBSERVED',active_scope=NULL,
            lease_token=NULL,next_attempt_at=NULL,last_error=NULL
        WHERE task_id=#{id} AND status<>'OBSERVED'
        """)
    int observed(@Param("id") String id);

    @Update("""
        UPDATE analysis_deliveries SET status='PENDING',lease_token=NULL,last_error=NULL,
          next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3))
        WHERE task_id=#{id}
        """)
    int requeue(@Param("id") String id,@Param("delay") int delay);

    @Update("""
        UPDATE analysis_deliveries d JOIN analysis_executions e ON e.task_id=d.task_id
        SET d.status='PENDING',d.lease_token=NULL,d.next_attempt_at=CURRENT_TIMESTAMP(3)
        WHERE e.state='QUEUED' AND d.status='OBSERVED'
          AND e.next_run_at<=CURRENT_TIMESTAMP(3)
          AND d.updated_at<TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(3))
        """)
    int recoverUnclaimed();

    @Select("""
        SELECT COUNT(*) FROM analysis_deliveries
        WHERE media_id=#{mediaId} AND goal_digest=#{digest} AND status<>'OBSERVED'
        """)
    int pending(@Param("mediaId") Long mediaId, @Param("digest") String digest);
}
