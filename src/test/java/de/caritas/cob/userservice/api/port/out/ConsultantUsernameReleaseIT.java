package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * ORISO-Admin#1489: a counsellor whose hard deletion cannot remove the row must not keep the
 * (unique) username, or nobody can create an account with that name again.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantUsernameReleaseIT {

  private static final String DELETED_ID = "c0ffee00-1489-4000-8000-000000000001";
  private static final String ACTIVE_ID = "c0ffee00-1489-4000-8000-000000000002";
  private static final String NEW_ID = "c0ffee00-1489-4000-8000-000000000003";
  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    inTransaction(
        () ->
            List.of(DELETED_ID, ACTIVE_ID, NEW_ID)
                .forEach(id -> consultants.findById(id).ifPresent(consultants::delete)));
    TenantContext.clear();
  }

  private void inTransaction(Runnable work) {
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> work.run());
  }

  @Test
  void releasesTheUsernameOfADeletedCounsellorSoANewAccountCanTakeIt() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    inTransaction(
        () -> {
          var deleted = counsellor(DELETED_ID, "reuse-1489");
          deleted.setDeleteDate(LONG_AGO);
          consultants.save(deleted);
          consultants.save(counsellor(ACTIVE_ID, "active-1489"));
        });

    assertThat(consultants.existsByUsernameAndDeleteDateIsNotNull("reuse-1489")).isTrue();
    assertThat(consultants.existsByUsernameAndDeleteDateIsNotNull("active-1489")).isFalse();

    // An active counsellor is never touched, only one marked for deletion.
    assertThat(consultants.releaseUsernameOfDeletedConsultant(ACTIVE_ID, "deleted:" + ACTIVE_ID))
        .isZero();
    assertThat(consultants.releaseUsernameOfDeletedConsultant(DELETED_ID, "deleted:" + DELETED_ID))
        .isEqualTo(1);

    assertThat(consultants.existsByUsernameAndDeleteDateIsNotNull("reuse-1489")).isFalse();
    assertThat(consultants.findById(DELETED_ID).orElseThrow().getUsername())
        .isEqualTo("deleted:" + DELETED_ID);
    assertThat(consultants.findById(ACTIVE_ID).orElseThrow().getUsername())
        .isEqualTo("active-1489");

    // The unique key no longer blocks the name.
    inTransaction(() -> consultants.save(counsellor(NEW_ID, "reuse-1489")));
    assertThat(consultants.findByUsernameAndDeleteDateIsNull("reuse-1489")).isPresent();
  }

  /** The release must stay committed when the caller's own transaction rolls back. */
  @Test
  void keepsTheReleaseWhenTheCallersTransactionRollsBack() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    inTransaction(
        () -> {
          var deleted = counsellor(DELETED_ID, "rollback-1489");
          deleted.setDeleteDate(LONG_AGO);
          consultants.save(deleted);
        });

    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              consultants.releaseUsernameOfDeletedConsultant(DELETED_ID, "deleted:" + DELETED_ID);
              status.setRollbackOnly();
            });

    assertThat(consultants.findById(DELETED_ID).orElseThrow().getUsername())
        .isEqualTo("deleted:" + DELETED_ID);
  }

  private Consultant counsellor(String id, String handle) {
    return Consultant.builder()
        .id(id)
        .matrixUserId("@" + handle + ":oriso.invalid")
        .username(handle)
        .firstName("Setup")
        .lastName("Proof " + id.substring(id.length() - 1))
        .email(handle + "@example.invalid")
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .notifyEnquiriesRepeating(true)
        .notifyNewChatMessageFromAdviceSeeker(true)
        .walkThroughEnabled(true)
        .languageCode(LanguageCode.de)
        .tenantId(26L)
        .status(ConsultantStatus.IN_PROGRESS)
        .createDate(LONG_AGO)
        .updateDate(LONG_AGO)
        .build();
  }
}
