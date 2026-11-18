package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConsultantAgencyRelationFinalizer.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConsultantAgencyRelationFinalizerPersistenceIT {
  private static final long TENANT_ID = 81L;
  private static final long OTHER_TENANT_ID = 82L;
  private static final long AGENCY_ID = 83L;

  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository assignments;
  @Autowired private ConsultantAgencyRelationFinalizer finalizer;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void finalizationUsesTheLatestTeamFlagWhenTheManagedConsultantWasLoadedEarlier() {
    var relation = newAssignment();
    var consultantId = relation.getConsultant().getId();

    TenantContext.runIn(
        TENANT_ID,
        () ->
            transaction()
                .executeWithoutResult(
                    status -> {
                      var earlier =
                          consultants.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();
                      assertThat(earlier.isTeamConsultant()).isTrue();
                      independentTransaction()
                          .executeWithoutResult(
                              change ->
                                  consultants
                                      .findById(consultantId)
                                      .orElseThrow()
                                      .setTeamConsultant(false));

                      assertThat(
                              finalizer.finalizeConsultantAgencyRelation(
                                  earlier,
                                  new AgencyDTO().id(AGENCY_ID).teamAgency(true),
                                  relation))
                          .isTrue();
                    }));

    TenantContext.runIn(
        TENANT_ID,
        () -> {
          var persisted = consultants.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();
          assertThat(persisted.getStatus()).isEqualTo(ConsultantStatus.CREATED);
          assertThat(persisted.isTeamConsultant()).isTrue();
        });
  }

  @Test
  void finalizationCannotUpdateAConsultantMovedOutsideTheCurrentTenant() {
    var relation = newAssignment();
    var consultantId = relation.getConsultant().getId();

    TenantContext.runIn(
        TENANT_ID,
        () ->
            transaction()
                .executeWithoutResult(
                    status -> {
                      var earlier =
                          consultants.findByIdAndDeleteDateIsNull(consultantId).orElseThrow();
                      TenantContext.runIn(
                          TenantContext.TECHNICAL_TENANT_ID,
                          () ->
                              independentTransaction()
                                  .executeWithoutResult(
                                      change ->
                                          consultants
                                              .findById(consultantId)
                                              .orElseThrow()
                                              .setTenantId(OTHER_TENANT_ID)));

                      assertThat(
                              finalizer.finalizeConsultantAgencyRelation(
                                  earlier, new AgencyDTO().id(AGENCY_ID), relation))
                          .isFalse();
                      assertThat(assignments.findById(relation.getId()).orElseThrow().getStatus())
                          .isEqualTo(ConsultantAgencyStatus.IN_PROGRESS);
                    }));
  }

  @Test
  void reusingTheRowLockKeepsThisTransactionsPendingConsultantChanges() {
    var relation = newAssignment();
    var consultantId = relation.getConsultant().getId();

    TenantContext.runIn(
        TENANT_ID,
        () ->
            transaction()
                .executeWithoutResult(
                    status -> {
                      var consultant =
                          consultants.findFreshActiveByIdForUpdate(consultantId).orElseThrow();
                      consultant.setDisplayName("Pending display name");

                      assertThat(
                              finalizer.finalizeConsultantAgencyRelation(
                                  consultant, new AgencyDTO().id(AGENCY_ID), relation))
                          .isTrue();
                      assertThat(consultant.getDisplayName()).isEqualTo("Pending display name");
                    }));

    TenantContext.runIn(
        TENANT_ID,
        () ->
            assertThat(
                    consultants
                        .findByIdAndDeleteDateIsNull(consultantId)
                        .orElseThrow()
                        .getDisplayName())
                .isEqualTo("Pending display name"));
  }

  private ConsultantAgency newAssignment() {
    return TenantContext.supplyAcrossTenants(
        () ->
            transaction()
                .execute(
                    status -> {
                      var id = UUID.randomUUID().toString();
                      var consultant =
                          consultants.save(
                              Consultant.builder()
                                  .id(id)
                                  .username(id)
                                  .firstName("Test")
                                  .lastName("Counsellor")
                                  .email(id + "@example.org")
                                  .encourage2fa(false)
                                  .magicLinkLoginEnabled(false)
                                  .notifyEnquiriesRepeating(false)
                                  .notifyNewChatMessageFromAdviceSeeker(false)
                                  .languageCode(LanguageCode.de)
                                  .tenantId(TENANT_ID)
                                  .status(ConsultantStatus.IN_PROGRESS)
                                  .teamConsultant(true)
                                  .build());
                      return assignments.save(
                          ConsultantAgency.builder()
                              .consultant(consultant)
                              .agencyId(AGENCY_ID)
                              .tenantId(TENANT_ID)
                              .status(ConsultantAgencyStatus.IN_PROGRESS)
                              .build());
                    }));
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private TransactionTemplate independentTransaction() {
    var transaction = transaction();
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }
}
