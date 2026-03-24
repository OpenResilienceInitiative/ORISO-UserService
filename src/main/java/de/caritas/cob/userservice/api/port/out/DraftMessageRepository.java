package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DraftMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftMessageRepository extends JpaRepository<DraftMessage, Long> {

  Optional<DraftMessage> findByUserIdAndScopeKey(String userId, String scopeKey);

  List<DraftMessage> findByUserIdOrderByUpdateDateDesc(String userId, Pageable pageable);

  void deleteByUserIdAndScopeKey(String userId, String scopeKey);
}


