package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.ConsultantTopic;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityTombstoneRepository;
import de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly job runs this step outside any transaction (ORISO-Admin#1523). On Staging and Dev not
 * one counsellor was hard-deleted: the supervision bulk delete needs a transaction, failed every
 * night, and the step returned before the row was touched. This test runs the step the same way.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=false"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  DeleteDatabaseConsultantAction.class,
  SessionOwnershipService.class,
  InquiryAcceptanceNoticeStore.class,
  IdentityTombstoneService.class,
  de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver.class
})
class DeleteDatabaseConsultantActionIT {
  @MockitoBean
  private de.caritas.cob.userservice.api.service.session.AgencyEnquiryRoomDecisions roomDecisions;

  @Autowired private DeleteDatabaseConsultantAction action;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private IdentityTombstoneRepository tombstoneRepository;
  @Autowired private JdbcTemplate jdbc;

  @MockitoBean private DeletionAuditService deletionAuditService;

  private final List<String> created = new ArrayList<>();
  private Long borrowedSessionId;
  private String borrowedSessionOwner;
  private Integer borrowedSessionStatus;

  @AfterEach
  void restore() {
    if (borrowedSessionId != null) {
      jdbc.update(
          "UPDATE session SET consultant_id = ?, status = ? WHERE id = ?",
          borrowedSessionOwner,
          borrowedSessionStatus,
          borrowedSessionId);
    }
    created.forEach(
        id -> {
          jdbc.update("DELETE FROM consultant_topic WHERE consultant_id = ?", id);
          jdbc.update("DELETE FROM language WHERE consultant_id = ?", id);
          jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", id);
        });
    tombstoneRepository.deleteAll();
  }

  @Test
  void aMarkedCounsellor_Should_BeDeleted_WhenTheStepRunsOutsideATransaction() {
    Consultant consultant = markedCounsellor();

    List<DeletionWorkflowError> errors = run(consultant);

    assertThat(errors).isEmpty();
    assertThat(consultantRepository.findById(consultant.getId())).isEmpty();
    assertThat(tombstoneRepository.findFirstBySubjectIdOrderByHardDeletedAtDesc(consultant.getId()))
        .isPresent();
  }

  @Test
  void aCounsellorWithTopicsAndLanguages_Should_BeDeletedCompletely() {
    Consultant consultant = markedCounsellor();
    jdbc.update(
        "INSERT INTO consultant_topic (id, consultant_id, topic_id, create_date, update_date)"
            + " VALUES (987654, ?, 1, ?, ?)",
        consultant.getId(),
        LocalDateTime.now(),
        LocalDateTime.now());
    jdbc.update(
        "INSERT INTO language (consultant_id, language_code) VALUES (?, ?)",
        consultant.getId(),
        LanguageCode.de.name());

    List<DeletionWorkflowError> errors = run(consultant);

    assertThat(errors).isEmpty();
    assertThat(consultantRepository.findById(consultant.getId())).isEmpty();
    assertThat(count("consultant_topic", consultant.getId())).isZero();
    assertThat(count("language", consultant.getId())).isZero();
  }

  @Test
  void theOwnerOfAClosedSession_Should_BeDeleted_AndTheSessionKeptWithoutOwner() {
    Consultant consultant = markedCounsellor();
    Long sessionId = borrowSessionFor(consultant, 3 /* DONE */);

    List<DeletionWorkflowError> errors = run(consultant);

    assertThat(errors).isEmpty();
    assertThat(consultantRepository.findById(consultant.getId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT consultant_id FROM session WHERE id = ?", String.class, sessionId))
        .isNull();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private List<DeletionWorkflowError> run(Consultant consultant) {
    var target = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    action.execute(target);
    return target.getDeletionWorkflowErrors();
  }

  private Consultant markedCounsellor() {
    String id = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();
    Consultant consultant =
        consultantRepository.save(
            Consultant.builder()
                .id(id)
                .username("u-" + id.substring(0, 8))
                .firstName("Deleted")
                .lastName("Counsellor")
                .email(id + "@example.invalid")
                .encourage2fa(true)
                .magicLinkLoginEnabled(false)
                .notifyEnquiriesRepeating(true)
                .notifyNewChatMessageFromAdviceSeeker(true)
                .walkThroughEnabled(false)
                .languageCode(LanguageCode.de)
                .status(ConsultantStatus.IN_DELETION)
                .deleteDate(now.minusDays(3))
                .deletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS)
                .languages(new HashSet<Language>())
                .consultantTopics(new HashSet<ConsultantTopic>())
                .createDate(now.minusDays(30))
                .updateDate(now)
                .build());
    created.add(id);
    return consultant;
  }

  private Long borrowSessionFor(Consultant consultant, int status) {
    var row =
        jdbc.queryForMap(
            "SELECT id, consultant_id, status FROM session ORDER BY id FETCH FIRST 1 ROWS ONLY");
    borrowedSessionId = ((Number) row.get("ID")).longValue();
    borrowedSessionOwner = (String) row.get("CONSULTANT_ID");
    borrowedSessionStatus = ((Number) row.get("STATUS")).intValue();
    jdbc.update(
        "UPDATE session SET consultant_id = ?, status = ? WHERE id = ?",
        consultant.getId(),
        status,
        borrowedSessionId);
    return borrowedSessionId;
  }

  private int count(String table, String consultantId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE consultant_id = ?", Integer.class, consultantId);
  }
}
