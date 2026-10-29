package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/** Executes the deleted-asker placeholder queries against the H2 testing schema (FE#1159). */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DeletedAskerSessionRepositoryIT {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

  @Autowired private DeletedAskerSessionRepository underTest;

  @Test
  void findsOnlyTheCounsellorsOwnPlaceholdersInsideTheWindow_newestFirst() {
    save("consultant-1", 1L, NOW.minusDays(40));
    save("consultant-1", 2L, NOW.minusDays(5));
    save("consultant-1", 3L, NOW.minusDays(1));
    save("consultant-2", 4L, NOW.minusDays(1));

    var found =
        underTest.findByConsultantIdAndDeletedAtAfterOrderByDeletedAtDesc(
            "consultant-1", NOW.minusDays(30));

    assertThat(found).extracting(DeletedAskerSession::getFormerSessionId).containsExactly(3L, 2L);
  }

  @Test
  void deleteDeletedBefore_removesOnlyExpiredPlaceholders() {
    save("consultant-1", 1L, NOW.minusDays(40));
    save("consultant-1", 2L, NOW.minusDays(5));

    assertThat(underTest.deleteDeletedBefore(NOW.minusDays(30))).isEqualTo(1);
    assertThat(underTest.findAll())
        .extracting(DeletedAskerSession::getFormerSessionId)
        .containsExactly(2L);
  }

  @Test
  void existsByConsultantIdAndFormerSessionId_detectsARetriedDeletion() {
    save("consultant-1", 1L, NOW);

    assertThat(underTest.existsByConsultantIdAndFormerSessionId("consultant-1", 1L)).isTrue();
    assertThat(underTest.existsByConsultantIdAndFormerSessionId("consultant-2", 1L)).isFalse();
  }

  private void save(String consultantId, Long formerSessionId, LocalDateTime deletedAt) {
    underTest.save(
        DeletedAskerSession.builder()
            .consultantId(consultantId)
            .formerSessionId(formerSessionId)
            .deletedAt(deletedAt)
            .build());
  }
}
