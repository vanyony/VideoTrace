package com.videotrace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CreationSchedulingConfig {
    // Preserve the default scheduler for analysis recovery and cleanup jobs.
    @Bean("taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("maintenance-"); }

    @Bean("creationTaskScheduler")
    public ThreadPoolTaskScheduler creationTaskScheduler() { return scheduler("creation-dispatch-"); }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
