package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.session.AgencyMembershipSyncListener;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * #1264: removing a centre deletes its topic rows. The relation and its topics must go together or
 * not at all, so these tests run without a test transaction and inject a failure mid-way.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantAgencyRemovalAtomicityIT {

  private static final String CONSULTANT_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";
  private static final long CENTRE_A = 258L;
  private static final long CENTRE_B = 743L;
  private static final long TOPIC = 9301L;

  @Autowired private ConsultantAgencyAdminService consultantAgencyAdminService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private ConsultantTopicRepository consultantTopicRepository;
  @MockitoBean private ConsultantAgencyDeletionValidationService deletionValidation;
  @MockitoBean private AgencyMembershipSyncListener agencyMembershipSyncListener;

  @BeforeEach
  void givenTopicsAtTwoCentres() {
    insertTopicRow(993011L, CENTRE_A);
    insertTopicRow(993012L, CENTRE_B);
  }

  @AfterEach
  void restore() {
    jdbcTemplate.update("DELETE FROM consultant_topic WHERE id IN (993011, 993012)");
    jdbcTemplate.update(
        "UPDATE consultant_agency SET delete_date = NULL"
            + " WHERE consultant_id = ? AND agency_id IN (?, ?)",
        CONSULTANT_ID,
        CENTRE_A,
        CENTRE_B);
  }

  @Test
  void singleCentreRemoval_rollsBackTheRelation_When_theTopicDeleteFails() {
    doAnswer(
            invocation -> {
              // Deletes like the real query, in the caller's transaction, then fails.
              jdbcTemplate.update(
                  "DELETE FROM consultant_topic WHERE consultant_id = ? AND agency_id = ?",
                  CONSULTANT_ID,
                  CENTRE_A);
              throw new IllegalStateException("injected after the topic delete");
            })
        .when(consultantTopicRepository)
        .deleteByConsultantIdAndAgencyId(CONSULTANT_ID, CENTRE_A);

    assertThatThrownBy(
            () ->
                consultantAgencyAdminService.markConsultantAgencyForDeletion(
                    CONSULTANT_ID, CENTRE_A))
        .isInstanceOf(IllegalStateException.class);

    assertThat(isDeleted(CENTRE_A)).isFalse();
    assertThat(topicRowsAt(CENTRE_A)).isEqualTo(1);
  }

  @Test
  void multiCentreRemoval_keepsTheFirstCentre_When_theSecondIsRefused() {
    doThrow(new IllegalStateException("refused"))
        .when(deletionValidation)
        .validateAndMarkForDeletion(argThat(relation -> relation.getAgencyId() == CENTRE_B));

    assertThatThrownBy(
            () ->
                consultantAgencyAdminService.markConsultantAgenciesForDeletion(
                    CONSULTANT_ID, List.of(CENTRE_A, CENTRE_B)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(isDeleted(CENTRE_A)).isFalse();
    assertThat(topicRowsAt(CENTRE_A)).isEqualTo(1);
  }

  private void insertTopicRow(long id, long agencyId) {
    jdbcTemplate.update(
        "INSERT INTO consultant_topic (id, consultant_id, topic_id, agency_id, create_date,"
            + " update_date) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        id,
        CONSULTANT_ID,
        TOPIC,
        agencyId);
  }

  private boolean isDeleted(long agencyId) {
    return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM consultant_agency WHERE consultant_id = ? AND agency_id = ?"
                + " AND delete_date IS NOT NULL",
            Integer.class,
            CONSULTANT_ID,
            agencyId)
        > 0;
  }

  private int topicRowsAt(long agencyId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM consultant_topic WHERE consultant_id = ? AND agency_id = ?",
        Integer.class,
        CONSULTANT_ID,
        agencyId);
  }
}
