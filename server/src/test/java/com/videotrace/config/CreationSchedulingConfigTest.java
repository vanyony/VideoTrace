package com.videotrace.config;

import com.videotrace.service.CreationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CreationSchedulingConfigTest {
    @Configuration
    @EnableScheduling
    static class Scheduling {}

    static class Jobs {
        final CountDownLatch creationStarted=new CountDownLatch(1);
        final CountDownLatch releaseCreation=new CountDownLatch(1);
        final CountDownLatch maintenanceRan=new CountDownLatch(1);

        @Scheduled(fixedDelay=10,scheduler="creationTaskScheduler")
        public void blockedCreation() {
            creationStarted.countDown();
            try { releaseCreation.await(); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Scheduled(fixedDelay=10)
        public void maintenance() {
            if(creationStarted.getCount()==0) maintenanceRan.countDown();
        }
    }

    @Test void blockedCreatorLeavesDefaultMaintenanceSchedulerAvailable() throws Exception {
        assertEquals("creationTaskScheduler",CreationService.class.getMethod("dispatch").getAnnotation(Scheduled.class).scheduler());
        Jobs jobs=new Jobs();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(CreationSchedulingConfig.class,Scheduling.class);
            context.registerBean(Jobs.class,()->jobs);
            try {
                context.refresh();
                assertTrue(jobs.creationStarted.await(2,TimeUnit.SECONDS));
                assertTrue(jobs.maintenanceRan.await(2,TimeUnit.SECONDS),"Analysis maintenance must run while creator HTTP work is blocked");
                assertEquals(1,jobs.releaseCreation.getCount());
            } finally { jobs.releaseCreation.countDown(); }
        }
    }
}
