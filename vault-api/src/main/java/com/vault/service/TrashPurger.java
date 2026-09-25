package com.vault.service;

import com.vault.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Permanently deletes files that have been in the trash longer than {@code vault.trash.retention-days}. */
@Component
public class TrashPurger {

    private static final Logger log = LoggerFactory.getLogger(TrashPurger.class);

    private final ObjectService objects;
    private final VaultProperties props;

    public TrashPurger(ObjectService objects, VaultProperties props) {
        this.objects = objects;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${vault.trash.purge-interval-seconds:3600}",
            initialDelayString = "${vault.trash.purge-interval-seconds:3600}", timeUnit = TimeUnit.SECONDS)
    void scheduledPurge() {
        try {
            int purged = purgeNow();
            if (purged > 0) {
                log.info("trash purge permanently deleted {} expired file(s)", purged);
            }
        } catch (Exception e) {
            log.error("trash purge failed", e);
        }
    }

    public int purgeNow() {
        return objects.purgeExpiredTrash(Instant.now().minus(Duration.ofDays(props.trash().retentionDays())));
    }
}
