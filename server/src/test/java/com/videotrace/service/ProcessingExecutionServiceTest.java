package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingExecutionServiceTest {
    private final ProcessingExecutionMapper mapper=mock(ProcessingExecutionMapper.class);
    private final ProcessingDeliveryMapper deliveries=mock(ProcessingDeliveryMapper.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private ProcessingExecutionService service;
    private final String id="00000000-0000-0000-0000-000000000007";

    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(mapper.claim(eq(id),anyString(),eq(120))).thenReturn(1);
        when(mapper.lockOwner(eq(id),anyString())).thenReturn(id);
        when(mapper.finish(eq(id),anyString(),anyString(),nullable(String.class))).thenReturn(1);
        when(mapper.find(id)).thenReturn(new ProcessingExecution(id,1L,7L,"RUNNING",1,null));
        service=new ProcessingExecutionService(mapper,deliveries,transactions,120,30,30);
    }
    @AfterEach void close() { service.close(); TaskExecutionContext.clear(); }
    private ProcessingTaskMsg message() {
        ProcessingTaskMsg m=new ProcessingTaskMsg(7L,ProcessingTaskMsg.PREPARE_SOURCE,"hash","goal");
        m.setTaskId(id); return m;
    }
    @Test void completedDeliveryCannotExecuteAgain() {
        when(mapper.claim(eq(id),anyString(),eq(120))).thenReturn(0);
        Runnable work=mock(Runnable.class);
        assertEquals(ProcessingExecutionService.Outcome.IGNORED,service.execute(message(),work));
        verifyNoInteractions(work);
    }
    @Test void lostTokenBlocksCheckpointAndFinalResultWrites() {
        AtomicBoolean wrote=new AtomicBoolean();
        when(mapper.lockOwner(eq(id),anyString())).thenReturn(null);
        assertEquals(ProcessingExecutionService.Outcome.IGNORED,
            service.execute(message(),() -> TaskExecutionContext.guarded(() -> wrote.set(true))));
        assertFalse(wrote.get());
        verify(mapper,never()).finish(anyString(),anyString(),anyString(),nullable(String.class));
        assertFalse(TaskExecutionContext.active());
    }
    @Test void successfulExecutionCommitsTerminalStateBeforeNotification() {
        assertEquals(ProcessingExecutionService.Outcome.SUCCEEDED,service.execute(message(),() -> {}));
        verify(mapper).finish(eq(id),anyString(),eq("SUCCEEDED"),isNull());
        verify(deliveries,never()).requeue(anyString(),anyInt());
    }
    @Test void temporaryFailureRequeuesAtomicallyAndPermanentFailureStops() {
        assertEquals(ProcessingExecutionService.Outcome.RETRYING,
            service.execute(message(),() -> { throw new IllegalStateException("network"); }));
        verify(mapper).finish(eq(id),anyString(),eq("QUEUED"),eq("IllegalStateException"));
        verify(deliveries).requeue(id,5);
        assertEquals(ProcessingExecutionService.Outcome.FAILED,
            service.execute(message(),() -> { throw new IllegalArgumentException("bad media"); }));
        verify(mapper).finish(eq(id),anyString(),eq("FAILED"),eq("IllegalArgumentException"));
    }
    @Test void thirdExecutionFailureDoesNotRetryForever() {
        when(mapper.find(id)).thenReturn(new ProcessingExecution(id,1L,7L,"RUNNING",3,null));
        assertEquals(ProcessingExecutionService.Outcome.FAILED,
            service.execute(message(),() -> { throw new IllegalStateException("network"); }));
        verify(deliveries,never()).requeue(anyString(),anyInt());
    }
    @Test void recoveryResendsOnlyTasksWhoseExpiredLeaseWasRecovered() {
        when(mapper.expired()).thenReturn(List.of(id));
        when(mapper.recover(id)).thenReturn(1);
        when(mapper.find(id)).thenReturn(new ProcessingExecution(id,1L,7L,"QUEUED",1,null));
        service.recover();
        verify(deliveries).recoverUnclaimed();
        verify(deliveries).requeue(id,0);
    }
    @Test void transcriptionLeaseRecoveryUsesTheSameDurableRetryPath() {
        ProcessingTaskMsg transcription=message(); transcription.setAction(ProcessingTaskMsg.TRANSCRIBE);
        when(mapper.expired()).thenReturn(List.of(id));
        when(mapper.recover(id)).thenReturn(1);
        when(mapper.find(id)).thenReturn(new ProcessingExecution(id,1L,7L,"QUEUED",1,null));
        service.recover();
        verify(deliveries).requeue(id,0);
        assertEquals(ProcessingExecutionService.Outcome.SUCCEEDED, service.execute(transcription, () -> {}));
        verify(mapper).finish(eq(id),anyString(),eq("SUCCEEDED"),isNull());
    }
    @Test void saturationIsDeferredWithoutSpendingBusinessFailureAttempts() {
        when(mapper.defer(eq(id),anyString(),eq(30))).thenReturn(1);
        assertEquals(ProcessingExecutionService.Outcome.RETRYING,service.execute(message(),() -> {
            throw new IllegalArgumentException("wrapper",new com.videotrace.exception.ResourceBusyException());
        }));
        verify(mapper).defer(eq(id),anyString(),eq(30));
        verify(deliveries).requeue(id,30);
        verify(mapper,never()).finish(anyString(),anyString(),anyString(),nullable(String.class));
    }

    @Test void taskIdentityLookupEnforcesOwnership() {
        assertThrows(SecurityException.class,() -> service.requireOwned(id,2L));
        assertEquals(7L,service.requireOwned(id,1L).mediaId());
    }
}
