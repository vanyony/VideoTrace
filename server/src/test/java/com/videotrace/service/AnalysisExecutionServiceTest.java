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

class AnalysisExecutionServiceTest {
    private final AnalysisExecutionMapper mapper=mock(AnalysisExecutionMapper.class);
    private final AnalysisDeliveryMapper deliveries=mock(AnalysisDeliveryMapper.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private AnalysisExecutionService service;
    private final String id="00000000-0000-0000-0000-000000000007";

    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(mapper.claim(eq(id),anyString(),eq(120))).thenReturn(1);
        when(mapper.lockOwner(eq(id),anyString())).thenReturn(id);
        when(mapper.finish(eq(id),anyString(),anyString(),nullable(String.class))).thenReturn(1);
        when(mapper.find(id)).thenReturn(new AnalysisExecution(id,1L,7L,"RUNNING",1,null));
        service=new AnalysisExecutionService(mapper,deliveries,transactions,120,30,30);
    }
    @AfterEach void close() { service.close(); TaskExecutionContext.clear(); }
    private AnalysisTaskMsg message() {
        AnalysisTaskMsg m=new AnalysisTaskMsg(7L,AnalysisTaskMsg.START_ANALYSIS,"hash","goal");
        m.setTaskId(id); return m;
    }
    @Test void completedDeliveryCannotExecuteAgain() {
        when(mapper.claim(eq(id),anyString(),eq(120))).thenReturn(0);
        Runnable work=mock(Runnable.class);
        assertEquals(AnalysisExecutionService.Outcome.IGNORED,service.execute(message(),work));
        verifyNoInteractions(work);
    }
    @Test void lostTokenBlocksCheckpointAndFinalResultWrites() {
        AtomicBoolean wrote=new AtomicBoolean();
        when(mapper.lockOwner(eq(id),anyString())).thenReturn(null);
        assertEquals(AnalysisExecutionService.Outcome.IGNORED,
            service.execute(message(),() -> TaskExecutionContext.guarded(() -> wrote.set(true))));
        assertFalse(wrote.get());
        verify(mapper,never()).finish(anyString(),anyString(),anyString(),nullable(String.class));
        assertFalse(TaskExecutionContext.active());
    }
    @Test void successfulExecutionCommitsTerminalStateBeforeNotification() {
        assertEquals(AnalysisExecutionService.Outcome.SUCCEEDED,service.execute(message(),() -> {}));
        verify(mapper).finish(eq(id),anyString(),eq("SUCCEEDED"),isNull());
        verify(deliveries,never()).requeue(anyString(),anyInt());
    }
    @Test void temporaryFailureRequeuesAtomicallyAndPermanentFailureStops() {
        assertEquals(AnalysisExecutionService.Outcome.RETRYING,
            service.execute(message(),() -> { throw new IllegalStateException("network"); }));
        verify(mapper).finish(eq(id),anyString(),eq("QUEUED"),eq("IllegalStateException"));
        verify(deliveries).requeue(id,5);
        assertEquals(AnalysisExecutionService.Outcome.FAILED,
            service.execute(message(),() -> { throw new IllegalArgumentException("bad media"); }));
        verify(mapper).finish(eq(id),anyString(),eq("FAILED"),eq("IllegalArgumentException"));
    }
    @Test void thirdExecutionFailureDoesNotRetryForever() {
        when(mapper.find(id)).thenReturn(new AnalysisExecution(id,1L,7L,"RUNNING",3,null));
        assertEquals(AnalysisExecutionService.Outcome.FAILED,
            service.execute(message(),() -> { throw new IllegalStateException("network"); }));
        verify(deliveries,never()).requeue(anyString(),anyInt());
    }
    @Test void recoveryResendsOnlyTasksWhoseExpiredLeaseWasRecovered() {
        when(mapper.expired()).thenReturn(List.of(id));
        when(mapper.recover(id)).thenReturn(1);
        when(mapper.find(id)).thenReturn(new AnalysisExecution(id,1L,7L,"QUEUED",1,null));
        service.recover();
        verify(deliveries).recoverUnclaimed();
        verify(deliveries).requeue(id,0);
    }
    @Test void transcriptionLeaseRecoveryUsesTheSameDurableRetryPath() {
        AnalysisTaskMsg transcription=message(); transcription.setAction(AnalysisTaskMsg.TRANSCRIBE);
        when(mapper.expired()).thenReturn(List.of(id));
        when(mapper.recover(id)).thenReturn(1);
        when(mapper.find(id)).thenReturn(new AnalysisExecution(id,1L,7L,"QUEUED",1,null));
        service.recover();
        verify(deliveries).requeue(id,0);
        assertEquals(AnalysisExecutionService.Outcome.SUCCEEDED, service.execute(transcription, () -> {}));
        verify(mapper).finish(eq(id),anyString(),eq("SUCCEEDED"),isNull());
    }
    @Test void saturationIsDeferredWithoutSpendingBusinessFailureAttempts() {
        when(mapper.defer(eq(id),anyString(),eq(30))).thenReturn(1);
        assertEquals(AnalysisExecutionService.Outcome.RETRYING,service.execute(message(),() -> {
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
