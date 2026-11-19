package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.JourneyRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

/**
 * The journey table and its version column (ORISO-UserService#1689): the version counts every save,
 * and a writer holding an old row loses instead of overwriting — which only a database proves.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class JourneyRecordRepositoryIT {

  @Autowired private JourneyRecordRepository repository;

  @Test
  void countsEverySaveAndKeepsLevelsApart() {
    var platform = repository.saveAndFlush(row("PLATFORM", 0L, "j1"));
    var layer = repository.saveAndFlush(row("TENANT", 7L, "j1"));
    assertThat(platform.getVersion()).isZero();

    platform.setBody("{\"id\":\"j1\",\"name\":\"Neu\"}");
    assertThat(repository.saveAndFlush(platform).getVersion()).isEqualTo(1L);

    assertThat(repository.findByScopeAndScopeIdOrderByJourneyId("TENANT", 7L))
        .extracting(JourneyRecord::getJourneyId)
        .containsExactly("j1");
    assertThat(repository.findByJourneyId("j1")).hasSize(2);
    assertThat(layer.getUpdateDate()).isNotNull();
  }

  @Test
  void aWriterWithAnOldRowLoses() {
    var saved = repository.saveAndFlush(row("PLATFORM", 0L, "j2"));
    var stale = row("PLATFORM", 0L, "j2");
    stale.setVersion(saved.getVersion());

    saved.setBody("{\"id\":\"j2\",\"name\":\"first\"}");
    repository.saveAndFlush(saved);

    stale.setBody("{\"id\":\"j2\",\"name\":\"second\"}");
    assertThatThrownBy(() -> repository.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  private static JourneyRecord row(String scope, Long scopeId, String journeyId) {
    var row = new JourneyRecord();
    row.setScope(scope);
    row.setScopeId(scopeId);
    row.setJourneyId(journeyId);
    row.setConversationType("AGENCY_COUNSELLING");
    row.setBody("{\"id\":\"" + journeyId + "\"}");
    row.setUpdatedBy("test");
    return row;
  }
}
