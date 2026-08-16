package com.beacon.api.profiles;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TriggerProfileRepository extends JpaRepository<TriggerProfile, UUID> {

    List<TriggerProfile> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
