package com.vault.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByOwnerIdOrderByNameAsc(String ownerId);

    Optional<ProjectEntity> findByProjectIdAndOwnerId(String projectId, String ownerId);
}
