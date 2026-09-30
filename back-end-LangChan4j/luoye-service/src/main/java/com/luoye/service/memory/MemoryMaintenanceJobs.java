package com.luoye.service.memory;

import com.luoye.service.memory.MemoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 向量补建、衰减复查与过期清理。 */
@Component
public class MemoryMaintenanceJobs {

    private final MemoryService memory;

    public MemoryMaintenanceJobs(MemoryService memory) {
        this.memory = memory;
    }

    @Scheduled(fixedDelay = 600_000L)
    public void backfill() {
        memory.backfillEmbeddings();
    }

    @Scheduled(cron = "0 25 * * * *")
    public void decay() {
        memory.decayAndPurge();
    }
}
