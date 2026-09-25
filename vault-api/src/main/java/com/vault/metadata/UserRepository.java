package com.vault.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByGoogleSub(String googleSub);

    Optional<UserEntity> findByEmail(String email);
}
