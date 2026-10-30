package com.videotrace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded by active preparation jobs; no analysis-agent trace history or cost budget. */
@Service
public class ProcessingTelemetry {
    private static final Logger log=LoggerFactory.getLogger(ProcessingTelemetry.class);
    private record Trace(Long mediaId,Map<String,Double> metrics) { }
    private final Map<String,Trace> active=new ConcurrentHashMap<>();
    private final ThreadLocal<String> current=new ThreadLocal<>();

    public String start(Long mediaId,String goal) {
        String id=UUID.randomUUID().toString();
        active.put(id,new Trace(mediaId,new ConcurrentHashMap<>())); current.set(id); return id;
    }
    public void increment(String id,String metric,long amount) {
        Trace trace=id==null?null:active.get(id);
        if(trace!=null) trace.metrics().merge(metric,(double)amount,Double::sum);
    }
    public void incrementCurrent(String metric,long amount) { increment(current.get(),metric,amount); }
    public void valueCurrent(String metric,double value) {
        Trace trace=current.get()==null?null:active.get(current.get());
        if(trace!=null) trace.metrics().put(metric,value);
    }
    public void flush(String id) {
        Trace trace=active.remove(id);
        if(trace!=null) log.info("source_preparation traceId={} mediaId={} metrics={}",id,trace.mediaId(),trace.metrics());
    }
    public void clear() { current.remove(); }
    public void deleteTask(Long mediaId) { active.entrySet().removeIf(e -> mediaId.equals(e.getValue().mediaId())); }
}
