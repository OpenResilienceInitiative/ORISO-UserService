package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DraftMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DraftMessageRepository extends JpaRepository<DraftMessage, Long> {

  Optional<DraftMessage> findByUserIdAndScopeKey(String userId, String scopeKey);

  List<DraftMessage> findByUserIdOrderByUpdateDateDesc(String userId, Pageable pageable);

  void deleteByUserIdAndScopeKey(String userId, String scopeKey);

  /**
   * Removes every draft of a user. Drafts hold the only counselling content stored unencrypted
   * server-side, so account deletion has to clear them (#983, KDG epic #1010).
   */
  @Transactional
  void deleteByUserId(String userId);
}
