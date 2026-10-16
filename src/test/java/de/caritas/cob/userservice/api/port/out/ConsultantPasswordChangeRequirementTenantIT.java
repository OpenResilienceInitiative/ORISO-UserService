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

  @Autowired private ConsultantRepository consultants;
  @Autowired private EntityManager entityManager;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void clearsTheRequirementOfACounsellorOfAnotherProvider() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    consultants.save(counsellorOfProvider(26L));
    entityManager.flush();
    entityManager.clear();

    TenantContext.setCurrentTenant(1L);
    assertThat(consultants.findByIdAndDeleteDateIsNull(ID)).isEmpty();
    assertThat(consultants.clearPasswordChangeRequired(ID)).isEqualTo(1);
    entityManager.clear();

    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    assertThat(consultants.findByIdAndDeleteDateIsNull(ID))
        .get()
        .extracting(Consultant::getPasswordChangeRequired)
        .isEqualTo(false);
  }

  private Consultant counsellorOfProvider(Long tenantId) {
    var now = LocalDateTime.now();
    var consultant =
        Consultant.builder()
            .id(ID)
            .matrixUserId("@pcr-1144:oriso.invalid")
            .username("pcr-1144")
            .firstName("Setup")
            .lastName("Proof")
            .email("pcr-1144@example.invalid")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .walkThroughEnabled(true)
            .languageCode(LanguageCode.de)
            .tenantId(tenantId)
            .status(ConsultantStatus.IN_PROGRESS)
            .createDate(now)
            .updateDate(now)
            .build();
    consultant.setPasswordChangeRequired(true);
    return consultant;
  }
}
