package com.vault.service;

import com.vault.metadata.UserEntity;
import com.vault.metadata.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** Creates the account on first sign-in, refreshes profile details afterwards. */
    @Transactional
    public UserEntity upsert(String subject, String email, String name, String picture) {
        String normalized = email.toLowerCase(Locale.ROOT).trim();
        UserEntity user = users.findByGoogleSub(subject).or(() -> users.findByEmail(normalized))
                .orElseGet(() -> new UserEntity(UUID.randomUUID().toString(), subject, normalized, name, picture));
        user.touch(normalized, name, picture);
        return users.save(user);
    }

    public Optional<UserEntity> find(String userId) {
        return users.findById(userId);
    }

    /** userId -> email, for showing owners to admins. */
    public Map<String, String> emailsOf(Collection<String> userIds) {
        Map<String, String> result = new HashMap<>();
        users.findAllById(userIds).forEach(u -> result.put(u.getUserId(), u.getEmail()));
        return result;
    }
}
