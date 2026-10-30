package com.videotrace.consumer;

import com.videotrace.dto.ProcessingTaskMsg;
import com.videotrace.service.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceProcessingConsumerTest {
    private final ProcessingDeliveryService deliveries=mock(ProcessingDeliveryService.class);
    private final ProcessingExecutionService executions=mock(ProcessingExecutionService.class);
    private final SourcePreparationService preparation=mock(SourcePreparationService.class);
    private final TranscriptionTaskService transcription=mock(TranscriptionTaskService.class);
    private final FailedProcessingTaskService failures=mock(FailedProcessingTaskService.class);
    private final RocketMQTemplate mq=mock(RocketMQTemplate.class);
    private final SourceProcessingConsumer consumer=new SourceProcessingConsumer(deliveries,executions,preparation,transcription,failures,mq,"dead");
    private ProcessingTaskMsg message(String action) {
        var msg=new ProcessingTaskMsg(7L,action,"hash","goal");msg.setTaskId("task");
        when(deliveries.observe(msg)).thenReturn(msg);
        return msg;
    }
    @Test void preparesSourcesOnlyThroughClaimedDurableExecution() {
        var msg=message(ProcessingTaskMsg.PREPARE_SOURCE);
        when(executions.execute(eq(msg),any())).thenAnswer(call -> { ((Runnable)call.getArgument(1)).run(); return ProcessingExecutionService.Outcome.SUCCEEDED; });
        consumer.onMessage(msg);
        verify(preparation).prepare(7L);verifyNoInteractions(transcription);
    }
    @Test void retiredAnalysisActionsBecomeTerminalInsteadOfExecutingOrRequeueingForever() {
        var msg=message("START_ANALYSIS");
        when(executions.execute(eq(msg),any())).thenAnswer(call -> {
            assertThrows(IllegalArgumentException.class,()->((Runnable)call.getArgument(1)).run());
            return ProcessingExecutionService.Outcome.FAILED;
        });
        consumer.onMessage(msg);
        verify(failures).record(eq(msg),eq(0L),any());verifyNoInteractions(preparation,transcription);
    }
    @Test void invalidMessagesCannotBeAcknowledgedWhenBothFailureSinksAreUnavailable() {
        var msg=message("UNKNOWN");msg.setTaskId(null);
        doThrow(new IllegalStateException()).when(failures).record(eq(msg),anyLong(),any());
        doThrow(new IllegalStateException()).when(mq).convertAndSend("dead",msg);
        assertThrows(IllegalStateException.class,()->consumer.onMessage(msg));
        verifyNoInteractions(executions,preparation,transcription);
    }
}
