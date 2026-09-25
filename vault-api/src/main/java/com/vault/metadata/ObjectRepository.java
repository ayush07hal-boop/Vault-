package com.vault.metadata;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ObjectRepository extends JpaRepository<ObjectEntity, String>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<ObjectEntity> {

    /** Targeted updates (not load-modify-save) so they can never overwrite a concurrent version commit. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ObjectEntity o set o.trashedAt = :at where o.objectId = :id and o.status = com.vault.metadata.ObjectStatus.ACTIVE")
    int setTrashedAt(@Param("id") String id, @Param("at") Instant at);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ObjectEntity o set o.projectId = :projectId where o.objectId = :id and o.status = com.vault.metadata.ObjectStatus.ACTIVE")
    int setProjectId(@Param("id") String id, @Param("projectId") String projectId);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ObjectEntity o set o.projectId = null where o.projectId = :projectId")
    int clearProject(@Param("projectId") String projectId);

    @Query("select o.objectId from ObjectEntity o where o.status = com.vault.metadata.ObjectStatus.ACTIVE and o.trashedAt is not null and o.trashedAt < :cutoff")
    List<String> findTrashedBefore(@Param("cutoff") Instant cutoff, Pageable page);

    @Query("select o.objectId from ObjectEntity o where o.status = com.vault.metadata.ObjectStatus.ACTIVE and o.ownerId = :owner and o.trashedAt is not null")
    List<String> findTrashedIdsOf(@Param("owner") String ownerId);

    /** (fileName, size) of everything the user stores, trash included (trash still occupies space). */
    @Query("select o.fileName, o.sizeBytes from ObjectEntity o where o.status = com.vault.metadata.ObjectStatus.ACTIVE and o.ownerId = :owner")
    List<Object[]> fileSizesOf(@Param("owner") String ownerId);

    @Query("select o.projectId, count(o) from ObjectEntity o where o.status = com.vault.metadata.ObjectStatus.ACTIVE and o.ownerId = :owner and o.trashedAt is null and o.projectId is not null group by o.projectId")
    List<Object[]> countByProjectOf(@Param("owner") String ownerId);

    /**
     * Optimistic concurrency control: succeeds only if nobody else advanced the version first.
     *
     * @return 1 if the update was applied, 0 on conflict
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ObjectEntity o
               set o.version = :newVersion, o.fileName = :fileName, o.sizeBytes = :size,
                   o.checksum = :checksum, o.updatedAt = :now
             where o.objectId = :id and o.version = :expected
               and o.status = com.vault.metadata.ObjectStatus.ACTIVE
            """)
    int compareAndSetVersion(@Param("id") String id, @Param("expected") long expected,
                             @Param("newVersion") long newVersion, @Param("fileName") String fileName,
                             @Param("size") long size, @Param("checksum") String checksum,
                             @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ObjectEntity o set o.status = :to, o.updatedAt = :now where o.objectId = :id and o.status = :from")
    int transition(@Param("id") String id, @Param("from") ObjectStatus from, @Param("to") ObjectStatus to,
                   @Param("now") Instant now);

    long countByStatus(ObjectStatus status);

    org.springframework.data.domain.Page<ObjectEntity> findByStatusAndFileNameContainingIgnoreCase(
            ObjectStatus status, String namePart, Pageable page);

    org.springframework.data.domain.Page<ObjectEntity> findByStatusAndOwnerIdAndFileNameContainingIgnoreCase(
            ObjectStatus status, String ownerId, String namePart, Pageable page);

    /** Logical bytes stored (one copy per object, ignoring replication). */
    @Query("select coalesce(sum(o.sizeBytes), 0) from ObjectEntity o where o.status = com.vault.metadata.ObjectStatus.ACTIVE")
    long sumActiveBytes();

    /**
     * Objects whose number of usable, current-version replicas differs from the replication factor,
     * or that carry broken replica rows. Only nodes in {@code usableNodeStatuses} count towards durability.
     */
    @Query("""
            select o.objectId from ObjectEntity o
             where o.status = com.vault.metadata.ObjectStatus.ACTIVE
               and (o.replicationFactor <> (
                        select count(r) from ReplicaEntity r
                         where r.objectId = o.objectId and r.version = o.version
                           and r.status = com.vault.metadata.ReplicaStatus.HEALTHY
                           and r.nodeId in (select n.nodeId from StorageNodeEntity n where n.status in :usableNodeStatuses))
                    or exists (
                        select 1 from ReplicaEntity b
                         where b.objectId = o.objectId
                           and b.status in (com.vault.metadata.ReplicaStatus.CORRUPTED,
                                            com.vault.metadata.ReplicaStatus.MISSING,
                                            com.vault.metadata.ReplicaStatus.OUTDATED)))
             order by o.objectId
            """)
    List<String> findObjectIdsNeedingRepair(@Param("usableNodeStatuses") Collection<NodeStatus> usableNodeStatuses,
                                            Pageable page);

    @Query("select o.objectId from ObjectEntity o where o.status = :status order by o.objectId")
    List<String> findIdsByStatus(@Param("status") ObjectStatus status, Pageable page);
}
