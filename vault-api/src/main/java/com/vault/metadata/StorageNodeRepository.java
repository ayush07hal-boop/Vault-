package com.vault.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StorageNodeRepository extends JpaRepository<StorageNodeEntity, String> {

    List<StorageNodeEntity> findByStatusIn(Collection<NodeStatus> statuses);

    long countByStatus(NodeStatus status);
}
