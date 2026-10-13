package com.videotrace.service;

import com.videotrace.dto.*;
import com.videotrace.mapper.AnalysisDeliveryMapper;
import com.videotrace.mapper.AnalysisExecutionMapper;
import com.videotrace.utils.AnalysisTaskKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AnalysisExecutionService {
    public enum Outcome { SUCCEEDED, FAILED, RETRYING, IGNORED }
    private static final Logger log=LoggerFactory.getLogger(AnalysisExecutionService.class);
    private final AnalysisExecutionMapper mapper;
    private final AnalysisDeliveryMapper deliveries;
    private final TransactionTemplate transaction;
    private final int leaseSeconds,heartbeatSeconds,deferSeconds;
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"analysis-lease-heartbeat"); t.setDaemon(true); return t;
    });

    public AnalysisExecutionService(AnalysisExecutionMapper mapper,AnalysisDeliveryMapper deliveries,
            PlatformTransactionManager transactions,
            @Value("${analysis.execution.lease-seconds:120}") int leaseSeconds,
            @Value("${analysis.execution.heartbeat-seconds:30}") int heartbeatSeconds,
            @Value("${analysis.execution.defer-seconds:30}") int deferSeconds) {
        if (deferSeconds<1 || heartbeatSeconds<1 || leaseSeconds<heartbeatSeconds*3)
            throw new IllegalArgumentException("Execution lease must allow at least three heartbeat intervals");
        this.mapper=mapper; this.deliveries=deliveries;
        this.transaction=new TransactionTemplate(transactions);
        this.leaseSeconds=leaseSeconds; this.heartbeatSeconds=heartbeatSeconds; this.deferSeconds=deferSeconds;
    }

    public void register(String id,Long userId,Long mediaId,String digest) {
        if (mapper.insert(id,userId,mediaId,digest,mediaId+":"+digest)!=1)
            throw new IllegalStateException("Execution task was not persisted");
    }

    public Outcome execute(AnalysisTaskMsg message,Runnable work) {
        String id=message.getTaskId(),token=UUID.randomUUID().toString();
        if (mapper.claim(id,token,leaseSeconds)!=1) return Outcome.IGNORED;
        AtomicBoolean lost=new AtomicBoolean();
        ScheduledFuture<?> heartbeat=heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (mapper.heartbeat(id,token,leaseSeconds)!=1) lost.set(true);
            } catch (RuntimeException e) {
                lost.set(true); // Fail closed; recovery waits for the existing lease to expire.
                log.warn("analysis_heartbeat_failed taskId={} type={}",id,e.getClass().getSimpleName());
            }
        },heartbeatSeconds,heartbeatSeconds,TimeUnit.SECONDS);
        TaskExecutionContext.bind(operation -> transaction.executeWithoutResult(status -> {
            if (lost.get() || mapper.lockOwner(id,token)==null)
                throw new TaskExecutionContext.LeaseLostException();
            operation.run();
        }));
        try {
            work.run();
            TaskExecutionContext.guarded(() -> {
                if (mapper.finish(id,token,"SUCCEEDED",null)!=1)
                    throw new TaskExecutionContext.LeaseLostException();
            });
            return Outcome.SUCCEEDED;
        } catch (RuntimeException e) {
            if (lost.get() || causedByLeaseLoss(e)) return Outcome.IGNORED;
            try {
                if (com.videotrace.exception.ResourceBusyException.causedBy(e)) {
                    TaskExecutionContext.guarded(() -> {
                        if (mapper.defer(id,token,deferSeconds)!=1) throw new TaskExecutionContext.LeaseLostException();
                        deliveries.requeue(id,deferSeconds);
                    });
                    return Outcome.RETRYING;
                }
                AnalysisExecution task=mapper.find(id);
                boolean terminal=permanent(e) || task==null || task.attempts()>=3;
                TaskExecutionContext.guarded(() -> {
                    if (mapper.finish(id,token,terminal?"FAILED":"QUEUED",e.getClass().getSimpleName())!=1)
                        throw new TaskExecutionContext.LeaseLostException();
                    if (!terminal) deliveries.requeue(id,Math.min(60,5*(task==null?1:task.attempts())));
                });
                return terminal?Outcome.FAILED:Outcome.RETRYING;
            } catch (RuntimeException recordingError) {
                log.warn("analysis_execution_record_failed taskId={} type={}",id,recordingError.getClass().getSimpleName());
                return Outcome.IGNORED; // Durable lease expiry still allows recovery.
            }
        } finally {
            heartbeat.cancel(false);
            TaskExecutionContext.clear();
        }
    }

    @Scheduled(fixedDelayString="${analysis.execution.scan-ms:10000}")
    public void recover() {
        try {
            deliveries.recoverUnclaimed();
            for (String id:mapper.expired()) {
                transaction.executeWithoutResult(status -> {
                    if (mapper.recover(id)!=1) return;
                    AnalysisExecution task=mapper.find(id);
                    if (task!=null && "QUEUED".equals(task.state())) deliveries.requeue(id,0);
                });
            }
        } catch (RuntimeException e) {
            log.warn("analysis_execution_recovery_failed type={}",e.getClass().getSimpleName());
        }
    }

    public AnalysisExecution snapshot(String id) { return mapper.find(id); }

    public AnalysisExecution latest(Long mediaId,String goal,AnalysisMode mode) {
        return mapper.latest(mediaId,AnalysisTaskKeys.goalDigest(goal,mode));
    }
    public AnalysisExecution requireOwned(String id,Long userId) {
        UUID.fromString(id);
        AnalysisExecution task=mapper.find(id);
        if (task==null) throw new NoSuchElementException("分析任务不存在");
        if (!Objects.equals(userId,task.userId())) throw new SecurityException("无权访问该分析任务");
        return task;
    }
    private boolean causedByLeaseLoss(Throwable e) {
        for (int i=0;e!=null && i<16;i++,e=e.getCause())
            if (e instanceof TaskExecutionContext.LeaseLostException) return true;
        return false;
    }
    private boolean permanent(Throwable e) {
        for (int i=0;e!=null && i<16;i++,e=e.getCause())
            if (e instanceof IllegalArgumentException || e instanceof SecurityException
                    || e instanceof NoSuchElementException || e instanceof AgentLoopService.BudgetExceededException) return true;
        return false;
    }
    @PreDestroy public void close() { heartbeats.shutdownNow(); }
}
