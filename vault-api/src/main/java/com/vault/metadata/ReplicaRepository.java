package com.vault.metadata;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReplicaRepository extends JpaRepository<ReplicaEntity, ReplicaId> {

    List<ReplicaEntity> findByObjectId(String objectId);

    Optional<ReplicaEntity> findByObjectIdAndNodeId(String objectId, String nodeId);

    List<ReplicaEntity> findByStatus(ReplicaStatus status, Pageable page);

    long countByObjectId(String objectId);

    @Query("select r.status, count(r) from ReplicaEntity r group by r.status")
    List<Object[]> countGroupedByStatus();

    long countByNodeId(String nodeId);

    @Query("select distinct r.objectId from ReplicaEntity r where r.nodeId = :nodeId")
    List<String> findObjectIdsByNodeId(@Param("nodeId") String nodeId);

    /** Least-recently verified replicas first (never-verified first). */
    @Query("""
            select r from ReplicaEntity r
             where r.status = com.vault.metadata.ReplicaStatus.HEALTHY
             order by r.lastVerified asc nulls first, r.objectId
            """)
    List<ReplicaEntity> findVerificationCandidates(Pageable page);

    @Query("""
            select r from ReplicaEntity r
             where r.nodeId = :nodeId and r.status = com.vault.metadata.ReplicaStatus.HEALTHY
             order by r.objectId
            """)
    List<ReplicaEntity> findHealthyOnNode(@Param("nodeId") String nodeId, Pageable page);

    /** Status change guarded by version so a stale observation cannot clobber a newer commit. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReplicaEntity r set r.status = :status
             where r.objectId = :objectId and r.nodeId = :nodeId and r.version = :version
            """)
    int updateStatusIfVersion(@Param("objectId") String objectId, @Param("nodeId") String nodeId,
                              @Param("version") long version, @Param("status") ReplicaStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReplicaEntity r set r.lastVerified = :at
             where r.objectId = :objectId and r.nodeId = :nodeId and r.version = :version
            """)
    int touchVerified(@Param("objectId") String objectId, @Param("nodeId") String nodeId,
                      @Param("version") long version, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ReplicaEntity r where r.objectId = :objectId and r.nodeId = :nodeId")
    int deleteReplica(@Param("objectId") String objectId, @Param("nodeId") String nodeId);
}
