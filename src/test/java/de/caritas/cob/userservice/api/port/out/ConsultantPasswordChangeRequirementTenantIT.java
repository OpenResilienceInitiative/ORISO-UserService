package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * #1699: a counsellor of provider 26 resets their password on a request that carries another
 * tenant. The tenant filter hides them from an entity lookup, so the requirement must be cleared
 * without one.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ConsultantPasswordChangeRequirementTenantIT {

  private static final String ID = "c0ffee00-1144-4000-8000-000000000026";
  private static final String OTHER_ID = "c0ffee00-1144-4000-8000-000000000027";
  private static final String DELETED_ID = "c0ffee00-1144-4000-8000-000000000028";
  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void clearsTheRequirementOfACounsellorOfAnotherProvider() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    consultants.save(counsellorOfProvider(ID, "pcr-1144", 26L));
    consultants.save(counsellorOfProvider(OTHER_ID, "pcr-1144-other", 26L));
    var deleted = counsellorOfProvider(DELETED_ID, "pcr-1144-deleted", 26L);
    deleted.setDeleteDate(LONG_AGO);
    consultants.save(deleted);
    entityManager.flush();
    entityManager.clear();

    TenantContext.setCurrentTenant(1L);
    assertThat(consultants.findByIdAndDeleteDateIsNull(ID)).isEmpty();
    var now = LocalDateTime.now();
    assertThat(consultants.clearPasswordChangeRequired(ID, now)).isEqualTo(1);
    assertThat(consultants.clearPasswordChangeRequired("unknown-consultant", now)).isZero();
    assertThat(consultants.clearPasswordChangeRequired(DELETED_ID, now)).isZero();
    entityManager.clear();

    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    var cleared = consultants.findById(ID).orElseThrow();
    assertThat(cleared.getPasswordChangeRequired()).isFalse();
    assertThat(cleared.getUpdateDate()).isAfter(LONG_AGO);
    // Only the counsellor of the link: another counsellor and a deleted one keep the requirement.
    assertThat(consultants.findById(OTHER_ID).orElseThrow().getPasswordChangeRequired()).isTrue();
    assertThat(consultants.findById(DELETED_ID).orElseThrow().getPasswordChangeRequired()).isTrue();
  }

  private Consultant counsellorOfProvider(String id, String handle, Long tenantId) {
    var consultant =
        Consultant.builder()
            .id(id)
            .matrixUserId("@" + handle + ":oriso.invalid")
            .username(handle)
            .firstName("Setup")
            .lastName("Proof")
            .email(handle + "@example.invalid")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .walkThroughEnabled(true)
            .languageCode(LanguageCode.de)
            .tenantId(tenantId)
            .status(ConsultantStatus.IN_PROGRESS)
            .createDate(LONG_AGO)
            .updateDate(LONG_AGO)
            .build();
    consultant.setPasswordChangeRequired(true);
    return consultant;
  }
}
