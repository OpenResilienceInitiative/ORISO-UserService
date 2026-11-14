package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class UserRepositoryDeletionFailureIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void recordHardDeleteFailure_Should_storeReasonAndTimeOnTheKeptRow() {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername("enc." + user.getUserId());
    user.setEmail(user.getUserId() + "@example.org");
    user.setTenantId(990_201L);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    entityManager.flush();
    var failedAt = LocalDateTime.of(2026, 11, 13, 3, 0, 12);

    int updated =
        userRepository.recordHardDeleteFailure(
            user.getUserId(), "DATABASE: Unable to delete user", failedAt);

    assertThat(updated).isOne();
    var stored = userRepository.findById(user.getUserId()).orElseThrow();
    assertThat(stored.getDeletionFailureReason()).isEqualTo("DATABASE: Unable to delete user");
    assertThat(stored.getDeletionFailedAt()).isEqualTo(failedAt);
    assertThat(userRepository.recordHardDeleteFailure("gone", "x", failedAt)).isZero();
  }
}
