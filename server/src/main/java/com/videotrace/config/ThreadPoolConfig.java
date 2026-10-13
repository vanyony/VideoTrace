package com.videotrace.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class ThreadPoolConfig {
    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor(
            @Value("${analysis.pools.ai.core:2}") int core,
            @Value("${analysis.pools.ai.max:4}") int max,
            @Value("${analysis.pools.ai.queue:20}") int queue) {
        return executor("AI-Thread-",core,max,queue);
    }

    @Bean("asrExecutor")
    public ThreadPoolTaskExecutor asrExecutor(
            @Value("${analysis.pools.asr.core:2}") int core,
            @Value("${analysis.pools.asr.max:4}") int max,
            @Value("${analysis.pools.asr.queue:8}") int queue) {
        return executor("ASR-Thread-",core,max,queue);
    }

    @Bean("ocrExecutor")
    public ThreadPoolTaskExecutor ocrExecutor(
            @Value("${analysis.pools.ocr.core:2}") int core,
            @Value("${analysis.pools.ocr.max:2}") int max,
            @Value("${analysis.pools.ocr.queue:4}") int queue) {
        return executor("OCR-Thread-",core,max,queue);
    }

    @Bean("modelCallExecutor")
    public ThreadPoolTaskExecutor modelCallExecutor(
            @Value("${analysis.pools.llm.core:2}") int core,
            @Value("${analysis.pools.llm.max:4}") int max,
            @Value("${analysis.pools.llm.queue:8}") int queue) {
        return executor("LLM-Thread-",core,max,queue);
    }

    private ThreadPoolTaskExecutor executor(String prefix,int core,int max,int queue) {
        if(core<1 || max<core || queue<0) throw new IllegalArgumentException("Invalid executor limits");
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core); executor.setMaxPoolSize(max); executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30); executor.initialize();
        return executor;
    }
}
