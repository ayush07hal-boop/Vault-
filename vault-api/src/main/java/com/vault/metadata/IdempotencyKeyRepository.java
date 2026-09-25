package com.vault.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {

    /** Plain INSERT so a concurrent duplicate fails on the primary key instead of silently merging. */
    @Transactional
    @Modifying
    @Query(value = "insert into idempotency_keys (idem_key, status, created_at) values (:key, 'IN_PROGRESS', :now)",
            nativeQuery = true)
    int reserve(@Param("key") String key, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update IdempotencyKeyEntity k set k.status = 'DONE', k.objectId = :objectId where k.key = :key")
    int complete(@Param("key") String key, @Param("objectId") String objectId);
}
