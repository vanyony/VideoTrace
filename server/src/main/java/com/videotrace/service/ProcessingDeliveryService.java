package com.videotrace.service;

import com.videotrace.dto.ProcessingScope;
import com.videotrace.dto.ProcessingTaskMsg;
import com.videotrace.mapper.ProcessingDeliveryMapper;
import com.videotrace.utils.ProcessingTaskKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Durable MQ handoff. OBSERVED denotes consumer receipt, never successful business execution. */
@Service
public class ProcessingDeliveryService {
    private static final Logger log=LoggerFactory.getLogger(ProcessingDeliveryService.class);
    private final ProcessingDeliveryMapper mapper;
    private final ObjectMapper json;
    private final RocketMQTemplate mq;
    private final TransactionTemplate transaction;
    private final String topic;
    private final ProcessingExecutionService executions;
    private final int leaseSeconds, ackSeconds, batchSize;

    public ProcessingDeliveryService(ProcessingDeliveryMapper mapper, ObjectMapper json,
            RocketMQTemplate mq, PlatformTransactionManager transactions,
            @Value("${rocketmq.topic.video-analysis:video-analysis-topic}") String topic,
            @Value("${analysis.delivery.lease-seconds:60}") int leaseSeconds,
            @Value("${analysis.delivery.ack-seconds:300}") int ackSeconds,
            @Value("${analysis.delivery.batch-size:50}") int batchSize, ProcessingExecutionService executions) {
        if (leaseSeconds<1 || ackSeconds<1 || batchSize<1 || batchSize>1000)
            throw new IllegalArgumentException("Invalid delivery limits");
        this.mapper=mapper; this.json=json; this.mq=mq;
        this.executions=executions;
        this.transaction=new TransactionTemplate(transactions);
        // Own the commit: the message must never precede its database record.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.topic=topic; this.leaseSeconds=leaseSeconds;
        this.ackSeconds=ackSeconds; this.batchSize=batchSize;
    }

    public String enqueue(Long userId, ProcessingTaskMsg message, Runnable prepare) {
        if (!message.hasSupportedAction()) throw new IllegalArgumentException("Unsupported source processing action");
        String id=UUID.randomUUID().toString();
        message.setTaskId(id);
        String digest=ProcessingTaskKeys.goalDigest(message.isTranscription()
                ? ProcessingTaskMsg.TRANSCRIPTION_GOAL : message.getUserGoal(),
                message.isTranscription() ? ProcessingScope.GENERAL : ProcessingScope.fromNullable(message.getMode()));
        String scope=(message.isTranscription()?"transcription:":"")+message.getMediaId()+":"+digest;
        String payload;
        try {
            payload=json.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize processing message",e);
        }
        transaction.executeWithoutResult(status -> {
            if (prepare!=null) prepare.run();
            if (mapper.insert(id,userId,message.getMediaId(),digest,
                    scope,payload)!=1)
                throw new IllegalStateException("Processing delivery was not persisted");
            if (message.isTranscription()) executions.registerTranscription(id,userId,message.getMediaId(),digest,scope);
            else executions.register(id,userId,message.getMediaId(),digest);
        });
        // A post-commit broker failure does not revoke acceptance.
        tryDeliver(id);
        return id;
    }

    @Scheduled(fixedDelayString="${analysis.delivery.scan-ms:10000}")
    public void recover() {
        try {
            for (String id:mapper.due(batchSize)) tryDeliver(id);
        } catch (RuntimeException e) {
            log.warn("analysis_delivery_scan_failed type={}",e.getClass().getSimpleName());
        }
    }

    void tryDeliver(String id) {
        String token=UUID.randomUUID().toString();
        try {
            if (mapper.claim(id,token,leaseSeconds)!=1) return;
            mq.convertAndSend(topic,decode(mapper.payload(id)));
            // Token CAS preserves an observation from a consumer faster than this sender.
            mapper.sent(id,token,ackSeconds);
        } catch (RuntimeException e) {
            try {
                int delay=Math.min(300,5<<Math.min(6,Math.max(0,mapper.attempts(id)-1)));
                mapper.retry(id,token,delay,e.getClass().getSimpleName());
            } catch (RuntimeException recordingError) {
                // The sending lease still expires when persisting the failure also fails.
                log.warn("analysis_delivery_retry_record_failed taskId={} type={}",
                        id,recordingError.getClass().getSimpleName());
            }
            log.warn("analysis_delivery_deferred taskId={} type={}",id,e.getClass().getSimpleName());
        }
    }

    public ProcessingTaskMsg observe(ProcessingTaskMsg incoming) {
        if (incoming.getTaskId()==null) return incoming; // Compatible with existing broker messages.
        UUID.fromString(incoming.getTaskId());
        String payload=mapper.payload(incoming.getTaskId());
        if (payload==null) throw new IllegalArgumentException("Unknown processing task");
        ProcessingTaskMsg canonical=decode(payload);
        if (!incoming.getTaskId().equals(canonical.getTaskId()))
            throw new IllegalArgumentException("Invalid persisted task identity");
        mapper.observed(incoming.getTaskId());
        return canonical;
    }

    public boolean isPending(Long mediaId,String goal,ProcessingScope mode) {
        return mapper.pending(mediaId,ProcessingTaskKeys.goalDigest(goal,mode))>0;
    }

    private ProcessingTaskMsg decode(String payload) {
        try {
            return json.readValue(payload,ProcessingTaskMsg.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid persisted processing message",e);
        }
    }
}
