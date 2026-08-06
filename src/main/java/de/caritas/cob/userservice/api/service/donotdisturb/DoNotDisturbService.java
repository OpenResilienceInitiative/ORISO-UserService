package de.caritas.cob.userservice.api.service.donotdisturb;

import de.caritas.cob.userservice.api.model.UserDoNotDisturb;
import de.caritas.cob.userservice.api.port.out.UserDoNotDisturbRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global per-user Do-Not-Disturb (decided 2026-07-18). Authoritative, server-readable and
 * cross-device: the frontend suppresses toast/sound/push while active, and notification emails are
 * gated on it. Active while {@code dndUntil} is in the future; auto-reverts when it passes.
 */
@Service
@RequiredArgsConstructor
public class DoNotDisturbService {

  private final @NonNull UserDoNotDisturbRepository repository;

  public boolean isInDoNotDisturb(String userId) {
    LocalDateTime until = getDndUntil(userId);
    return until != null && until.isAfter(LocalDateTime.now());
  }

  public LocalDateTime getDndUntil(String userId) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return repository.findByUserId(userId).map(UserDoNotDisturb::getDndUntil).orElse(null);
  }

  @Transactional
  public void setDndUntil(String userId, LocalDateTime until) {
    if (userId == null || userId.isBlank()) {
      return;
    }
    UserDoNotDisturb entry =
        repository
            .findByUserId(userId)
            .orElseGet(() -> UserDoNotDisturb.builder().userId(userId).build());
    entry.setDndUntil(until);
    entry.setUpdateDate(LocalDateTime.now());
    repository.save(entry);
  }

  @Transactional
  public void clear(String userId) {
    setDndUntil(userId, null);
  }
}
