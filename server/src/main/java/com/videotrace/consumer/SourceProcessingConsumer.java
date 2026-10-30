package com.videotrace.consumer;

import com.videotrace.dto.ProcessingTaskMsg;
import com.videotrace.service.*;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic="${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup="${rocketmq.consumer.group:video-analysis-group}",maxReconsumeTimes=2)
public class SourceProcessingConsumer implements RocketMQListener<ProcessingTaskMsg>,RocketMQPushConsumerLifecycleListener {
    private final ProcessingDeliveryService deliveries;
    private final ProcessingExecutionService executions;
    private final SourcePreparationService preparation;
    private final TranscriptionTaskService transcription;
    private final FailedProcessingTaskService failures;
    private final RocketMQTemplate mq;
    private final String deadTopic;
    @Value("${analysis.consumer.min:2}") private int consumerMin=2;
    @Value("${analysis.consumer.max:4}") private int consumerMax=4;

    public SourceProcessingConsumer(ProcessingDeliveryService deliveries,ProcessingExecutionService executions,
            SourcePreparationService preparation,TranscriptionTaskService transcription,FailedProcessingTaskService failures,
            RocketMQTemplate mq,@Value("${rocketmq.topic.video-analysis-dead:video-analysis-dead-topic}") String deadTopic) {
        this.deliveries=deliveries;this.executions=executions;this.preparation=preparation;
        this.transcription=transcription;this.failures=failures;this.mq=mq;this.deadTopic=deadTopic;
    }
    @Override public void prepareStart(org.apache.rocketmq.client.consumer.DefaultMQPushConsumer consumer) {
        if(consumerMin<1 || consumerMax<consumerMin) throw new IllegalArgumentException("Invalid consumer concurrency");
        consumer.setConsumeThreadMin(consumerMin);consumer.setConsumeThreadMax(consumerMax);
    }
    @Override public void onMessage(ProcessingTaskMsg incoming) {
        if(incoming==null) return;
        ProcessingTaskMsg msg;
        try {
            msg=deliveries.observe(incoming);
            if(msg.getTaskId()==null || msg.getMediaId()==null)
                throw new IllegalArgumentException("Unsupported source processing message");
        } catch(IllegalArgumentException e) {
            // Old analysis messages are retired; preserve a failure record or a dead-letter copy.
            try { failures.record(incoming,0,e); }
            catch(RuntimeException recordingError) { mq.convertAndSend(deadTopic,incoming); }
            return;
        }
        var outcome=executions.execute(msg,() -> {
            if(!msg.hasSupportedAction()) throw new IllegalArgumentException("Retired analysis action");
            if(msg.isTranscription()) transcription.transcribe(msg.getMediaId());
            else preparation.prepare(msg.getMediaId());
        });
        if(outcome==ProcessingExecutionService.Outcome.FAILED) {
            var task=executions.snapshot(msg.getTaskId());
            try { failures.record(msg,task==null?0:task.attempts(),new IllegalStateException(task==null?"TaskFailed":task.lastError())); }
            catch(RuntimeException e) { mq.convertAndSend(deadTopic,msg); }
        }
        if(msg.isTranscription()) transcription.publishOutcome(msg.getMediaId(),outcome);
    }
}
