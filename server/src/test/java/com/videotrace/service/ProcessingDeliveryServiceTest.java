package com.videotrace.service;

import com.videotrace.dto.ProcessingTaskMsg;
import com.videotrace.mapper.ProcessingDeliveryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingDeliveryServiceTest {
    private final ProcessingDeliveryMapper mapper=mock(ProcessingDeliveryMapper.class);
    private final RocketMQTemplate mq=mock(RocketMQTemplate.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final TransactionStatus status=mock(TransactionStatus.class);
    private final ObjectMapper json=new ObjectMapper();
    private ProcessingDeliveryService service;
    private final ProcessingExecutionService executions=mock(ProcessingExecutionService.class);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(mapper.insert(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        when(mapper.claim(anyString(),anyString(),eq(60))).thenReturn(1);
        when(mapper.attempts(anyString())).thenReturn(1);
        service=new ProcessingDeliveryService(mapper,json,mq,transactions,"analysis",60,300,50,executions);
    }

    private ProcessingTaskMsg message() {
        return new ProcessingTaskMsg(7L,ProcessingTaskMsg.PREPARE_SOURCE,"hash","goal","GENERAL");
    }

    @Test
    void transcriptionIsPersistedInItsOwnNamespaceBeforeSending() throws Exception {
        ProcessingTaskMsg message=new ProcessingTaskMsg(7L,ProcessingTaskMsg.TRANSCRIBE,"hash",ProcessingTaskMsg.TRANSCRIPTION_GOAL);
        when(mapper.payload(anyString())).thenAnswer(c -> json.writeValueAsString(message));
        String id=service.enqueue(1L,message,null);
        verify(mapper).insert(eq(id),eq(1L),eq(7L),anyString(),startsWith("transcription:7:"),anyString());
        InOrder order=inOrder(executions,transactions,mq);
        order.verify(executions).registerTranscription(eq(id),eq(1L),eq(7L),anyString(),startsWith("transcription:7:"));
        order.verify(transactions).commit(status);
        order.verify(mq).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
    }

    @Test
    void committedIntentSurvivesBrokerFailureAndReturnsAcceptedIdentity() throws Exception {
        ProcessingTaskMsg message=message();
        when(mapper.payload(anyString())).thenAnswer(c -> json.writeValueAsString(message));
        doThrow(new IllegalStateException("broker unavailable")).when(mq).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
        String id=assertDoesNotThrow(() -> service.enqueue(1L,message,null));
        assertNotNull(UUID.fromString(id));
        assertEquals(id,message.getTaskId());
        InOrder order=inOrder(mapper,transactions,mq);
        order.verify(mapper).insert(eq(id),eq(1L),eq(7L),anyString(),anyString(),anyString());
        order.verify(transactions).commit(status);
        order.verify(mapper).claim(eq(id),anyString(),eq(60));
        order.verify(mapper).payload(id);
        order.verify(mq).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
        verify(mapper).retry(eq(id),anyString(),eq(5),eq("IllegalStateException"));
        verify(mapper,never()).sent(anyString(),anyString(),anyInt());
    }

    @Test
    void databaseFailureRollsBackRevisionAndNeverSendsMessage() {
        Runnable prepare=mock(Runnable.class);
        when(mapper.insert(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,() -> service.enqueue(1L,message(),prepare));
        verify(prepare).run();
        verify(transactions).rollback(status);
        verify(transactions,never()).commit(any());
        verifyNoInteractions(mq);
    }

    @Test
    void recoveringScannersSendOnlyWhenTheyWinTheDatabaseClaim() throws Exception {
        ProcessingTaskMsg message=message();
        String id=UUID.randomUUID().toString();
        message.setTaskId(id);
        when(mapper.due(50)).thenReturn(List.of(id));
        when(mapper.payload(id)).thenReturn(json.writeValueAsString(message));
        when(mapper.claim(eq(id),anyString(),eq(60))).thenReturn(1,0);
        service.recover();
        service.recover();
        verify(mq,times(1)).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
    }

    @Test
    void fastConsumerReceiptIsNotRevokedBySenderBookkeeping() throws Exception {
        ProcessingTaskMsg message=message();
        String id=UUID.randomUUID().toString();
        message.setTaskId(id);
        when(mapper.payload(id)).thenReturn(json.writeValueAsString(message));
        // The SQL token condition returns zero if OBSERVED won the race.
        when(mapper.sent(eq(id),anyString(),eq(300))).thenReturn(0);
        doAnswer(c -> { service.observe(message); return null; })
                .when(mq).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
        service.tryDeliver(id);
        InOrder order=inOrder(mapper);
        order.verify(mapper).claim(eq(id),anyString(),eq(60));
        order.verify(mapper,times(2)).payload(id);
        order.verify(mapper).observed(id);
        order.verify(mapper).sent(eq(id),anyString(),eq(300));
        verify(mapper,never()).retry(anyString(),anyString(),anyInt(),anyString());
    }

    @Test
    void consumerUsesPersistedParametersInsteadOfAlteredBrokerFields() throws Exception {
        String id=UUID.randomUUID().toString();
        ProcessingTaskMsg original=message();
        original.setTaskId(id);
        when(mapper.payload(id)).thenReturn(json.writeValueAsString(original));
        ProcessingTaskMsg altered=new ProcessingTaskMsg(999L,ProcessingTaskMsg.PREPARE_SOURCE,"other","other","REVIEW");
        altered.setTaskId(id);
        ProcessingTaskMsg canonical=service.observe(altered);
        assertEquals(7L,canonical.getMediaId());
        assertEquals("goal",canonical.getUserGoal());
        verify(mapper).observed(id);
    }

    @Test
    void databaseFailureRecordingReceiptPreventsSilentAcknowledgement() throws Exception {
        String id=UUID.randomUUID().toString();
        ProcessingTaskMsg message=message();
        message.setTaskId(id);
        when(mapper.payload(id)).thenReturn(json.writeValueAsString(message));
        doThrow(new IllegalStateException("db unavailable")).when(mapper).observed(id);
        assertThrows(IllegalStateException.class,() -> service.observe(message));
    }

    @Test
    void failureAfterBrokerSendIsRetriedFromDurableIntent() throws Exception {
        String id=UUID.randomUUID().toString();
        ProcessingTaskMsg message=message();
        message.setTaskId(id);
        when(mapper.payload(id)).thenReturn(json.writeValueAsString(message));
        when(mapper.sent(eq(id),anyString(),eq(300))).thenThrow(new IllegalStateException("db unavailable"));
        assertDoesNotThrow(() -> service.tryDeliver(id));
        verify(mq).convertAndSend(eq("analysis"),any(ProcessingTaskMsg.class));
        verify(mapper).retry(eq(id),anyString(),eq(5),eq("IllegalStateException"));
    }
}
