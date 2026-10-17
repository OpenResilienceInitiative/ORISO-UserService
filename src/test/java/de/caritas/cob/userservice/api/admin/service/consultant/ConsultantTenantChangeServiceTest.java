package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyCreationInput;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.AgencyOutsideTenantException;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.TopicsNotCoveredException;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UnknownAgencyException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantTenantChangeAudit;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTenantChangeAuditRepository;
import de.caritas.cob.userservice.api.port.out.IdentityTenantUpdater;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ConsultantTenantChangeServiceTest {

  private static final String CONSULTANT_ID = "mover";
  private static final String ACTOR_ID = "platform-admin";
  private static final long OLD_TENANT = 7L;
  private static final long NEW_TENANT = 8L;

  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantAgencyAdminService consultantAgencyAdminService;
  @Mock private ConsultantAgencyRelationCreatorService relationCreatorService;
  @Mock private ConsultantTopicAgencyCompatibilityValidator topicAgencyValidator;
  @Mock private SessionRepository sessionRepository;
  @Mock private CaseHandoverMatrixRepairService matrixRepairService;
  @Mock private OldTraegerRoomFinder roomFinder;
  @Mock private SessionSupervisorRepository sessionSupervisorRepository;
  @Mock private IdentityTenantUpdater identityTenantUpdater;
  @Mock private AdminRepository adminRepository;
  @Mock private ConsultantTenantChangeAuditRepository auditRepository;

  private ConsultantTenantChangeService service;
  private Consultant consultant;

  @BeforeEach
  void setUp() {
    service =
        new ConsultantTenantChangeService(
            consultantRepository,
            consultantAgencyRepository,
            consultantAgencyAdminService,
            relationCreatorService,
            topicAgencyValidator,
            sessionRepository,
            sessionSupervisorRepository,
            roomFinder,
            matrixRepairService,
            identityTenantUpdater,
            adminRepository,
            auditRepository,
            Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneOffset.UTC));
    ReflectionTestUtils.setField(service, "multiTenancyEnabled", true);
    consultant =
        Consultant.builder()
            .id(CONSULTANT_ID)
            .username("mover")
            .firstName("Mover")
            .lastName("Proof")
            .email("mover@example.invalid")
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .walkThroughEnabled(true)
            .languageCode(com.neovisionaries.i18n.LanguageCode.de)
            .matrixUserId("@mover:matrix")
            .tenantId(OLD_TENANT)
            .assignedSupervisorId("old-supervisor")
            .teamConsultant(true)
            .consultantAgencies(new HashSet<>())
            .build();
  }

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void refusesToMoveWhenMultiTenancyIsOff() {
    ReflectionTestUtils.setField(service, "multiTenancyEnabled", false);

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .satisfies(refusal(HttpStatus.BAD_REQUEST, "TENANT_CHANGE_MULTITENANCY_OFF"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void reportsAnUnknownConsultantAsNotFound() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> move(NEW_TENANT, 800L)).isInstanceOf(NotFoundException.class);

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void refusesToMoveToTheTraegerTheConsultantAlreadyBelongsTo() {
    found();

    assertThatThrownBy(() -> move(OLD_TENANT, 700L))
        .satisfies(refusal(HttpStatus.BAD_REQUEST, "TENANT_CHANGE_SAME_TENANT"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void refusesAnAccountThatAlsoHoldsAnAdminIdentity() {
    found();
    when(adminRepository.existsById(CONSULTANT_ID)).thenReturn(true);

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .satisfies(refusal(HttpStatus.CONFLICT, "TENANT_CHANGE_ADMIN_IDENTITY"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void refusesAnAgencyThatDoesNotBelongToTheNewTraegerBeforeChangingAnything() {
    found();
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(
            any(), eq(List.of(700L)), eq(NEW_TENANT)))
        .thenThrow(new AgencyOutsideTenantException("agency 700 does not belong to tenant 8"));

    assertThatThrownBy(() -> move(NEW_TENANT, 700L))
        .satisfies(refusal(HttpStatus.BAD_REQUEST, "TENANT_CHANGE_AGENCY_OTHER_TENANT"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
    assertThat(consultant.getTenantId()).isEqualTo(OLD_TENANT);
  }

  @Test
  void refusesAnUnknownAgencyWithItsOwnReason() {
    found();
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenThrow(new UnknownAgencyException("Agency ids [999] are not valid agencies"));

    assertThatThrownBy(() -> move(NEW_TENANT, 999L))
        .satisfies(refusal(HttpStatus.BAD_REQUEST, "TENANT_CHANGE_AGENCY_UNKNOWN"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void refusesTopicsTheNewCentresDoNotCoverWithTheirOwnReason() {
    found();
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenThrow(new TopicsNotCoveredException("topic ids [5] are not covered"));

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .satisfies(refusal(HttpStatus.BAD_REQUEST, "TENANT_CHANGE_TOPICS_NOT_COVERED"));

    verifyNoInteractions(identityTenantUpdater, consultantAgencyAdminService, auditRepository);
  }

  @Test
  void movesTheConsultantAndLeavesTheCasesWithTheOldTraeger() {
    found();
    oldAgencies(700L, 701L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(
            List.of(5L), List.of(800L, 801L), NEW_TENANT))
        .thenReturn(Map.of(800L, Set.of(5L)));
    when(sessionRepository.countByConsultantIdAndStatusIn(
            CONSULTANT_ID, List.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS)))
        .thenReturn(3L);
    when(roomFinder.roomsToLeave(CONSULTANT_ID, "@mover:matrix", OLD_TENANT, List.of(700L, 701L)))
        .thenReturn(List.of(new RoomRef(1L, "!a:matrix"), new RoomRef(2L, "!b")));
    when(matrixRepairService.enqueueLeaves(eq("@mover:matrix"), eq(CONSULTANT_ID), anyList()))
        .thenReturn(2);
    var created = createdRelationsRecorder();

    var result =
        service.changeTenant(
            CONSULTANT_ID, NEW_TENANT, List.of(agency(800L), agency(801L)), List.of(5L), ACTOR_ID);

    assertThat(result.consultantId()).isEqualTo(CONSULTANT_ID);
    assertThat(result.previousTenantId()).isEqualTo(OLD_TENANT);
    assertThat(result.tenantId()).isEqualTo(NEW_TENANT);
    assertThat(result.agencyIds()).containsExactly(800L, 801L);
    assertThat(result.casesLeftWithPreviousTenant()).isEqualTo(3);
    assertThat(result.matrixAccessRevocationsQueued()).isEqualTo(2);

    // The old centres go first, so the topics and the roster of the new Träger are validated
    // against the new centres only; the tenant must already be the new one when relations are made.
    InOrder order =
        inOrder(
            consultantAgencyAdminService,
            matrixRepairService,
            relationCreatorService,
            identityTenantUpdater);
    order.verify(matrixRepairService).cancelPendingLeavesInTenant("@mover:matrix", NEW_TENANT);
    order
        .verify(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion(CONSULTANT_ID, List.of(700L, 701L));
    order
        .verify(relationCreatorService, org.mockito.Mockito.times(2))
        .prepareConsultantAgencyRelation(any());
    order
        .verify(relationCreatorService, org.mockito.Mockito.times(2))
        .completeConsultantAgencyAssigment(any(), any());
    order.verify(identityTenantUpdater).updateTenant(CONSULTANT_ID, NEW_TENANT);

    assertThat(created.tenantAtCreation).containsExactly(NEW_TENANT, NEW_TENANT);
    assertThat(created.additionalAgencyIds)
        .allSatisfy(ids -> assertThat(ids).containsExactlyInAnyOrder(800L, 801L));
    assertThat(consultant.getTenantId()).isEqualTo(NEW_TENANT);
    assertThat(consultant.getAssignedSupervisorId()).isNull();
    assertThat(consultant.isTeamConsultant()).isFalse();
    assertThat(consultant.getConsultantTopics())
        .extracting("agencyId", "topicId")
        .containsExactly(org.assertj.core.groups.Tuple.tuple(800L, 5L));
    verify(consultantRepository).clearAssignedSupervisor(CONSULTANT_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<RoomRef>> rooms = ArgumentCaptor.forClass(List.class);
    verify(matrixRepairService)
        .enqueueLeaves(eq("@mover:matrix"), eq(CONSULTANT_ID), rooms.capture());
    assertThat(rooms.getValue())
        .containsExactly(new RoomRef(1L, "!a:matrix"), new RoomRef(2L, "!b"));
  }

  /** #1421 finding 2: the supervisions end with the move, after the rooms were looked up. */
  @Test
  void endsTheActiveSupervisionsOfTheMoverAfterTheirRoomsWereCollected() {
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());

    service.changeTenant(CONSULTANT_ID, NEW_TENANT, List.of(agency(800L)), null, ACTOR_ID);

    InOrder order = inOrder(roomFinder, sessionSupervisorRepository);
    order.verify(roomFinder).roomsToLeave(eq(CONSULTANT_ID), anyString(), eq(OLD_TENANT), any());
    order
        .verify(sessionSupervisorRepository)
        .endActiveSupervisionsOf(CONSULTANT_ID, LocalDateTime.parse("2026-10-16T09:00:00"));
  }

  @Test
  void endsTheSupervisionsEvenForAConsultantWithoutChatAccount() {
    consultant.setMatrixUserId(null);
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());

    service.changeTenant(CONSULTANT_ID, NEW_TENANT, List.of(agency(800L)), null, ACTOR_ID);

    verify(sessionSupervisorRepository)
        .endActiveSupervisionsOf(CONSULTANT_ID, LocalDateTime.parse("2026-10-16T09:00:00"));
  }

  @Test
  void writesAnAuditEntryWithAccountsAndCountsOnly() {
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());
    when(sessionRepository.countByConsultantIdAndStatusIn(anyString(), anyCollection()))
        .thenReturn(4L);
    when(roomFinder.roomsToLeave(anyString(), anyString(), anyLong(), anyCollection()))
        .thenReturn(List.of(new RoomRef(1L, "!a:matrix")));
    when(matrixRepairService.enqueueLeaves(anyString(), anyString(), anyList())).thenReturn(1);

    service.changeTenant(CONSULTANT_ID, NEW_TENANT, List.of(agency(800L)), null, ACTOR_ID);

    var saved = ArgumentCaptor.forClass(ConsultantTenantChangeAudit.class);
    verify(auditRepository).save(saved.capture());
    var audit = saved.getValue();
    assertThat(audit.getConsultantId()).isEqualTo(CONSULTANT_ID);
    assertThat(audit.getActorId()).isEqualTo(ACTOR_ID);
    assertThat(audit.getPreviousTenantId()).isEqualTo(OLD_TENANT);
    assertThat(audit.getNewTenantId()).isEqualTo(NEW_TENANT);
    assertThat(audit.getPreviousAgencyIds()).isEqualTo("700");
    assertThat(audit.getNewAgencyIds()).isEqualTo("800");
    assertThat(audit.getCasesLeftWithPreviousTenant()).isEqualTo(4);
    assertThat(audit.getMatrixAccessRevocationsQueued()).isEqualTo(1);
    assertThat(audit.getCreateDate()).isEqualTo(LocalDateTime.parse("2026-10-16T09:00:00"));
  }

  @Test
  void queuesNoRoomLeavesForAConsultantWithoutChatAccount() {
    consultant.setMatrixUserId(null);
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());

    var result =
        service.changeTenant(CONSULTANT_ID, NEW_TENANT, List.of(agency(800L)), null, ACTOR_ID);

    assertThat(result.matrixAccessRevocationsQueued()).isZero();
    verifyNoInteractions(matrixRepairService, roomFinder);
  }

  @Test
  void stopsBeforeTheIdentityProviderWhenTheLastConsultantGuardRejects() {
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());
    doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason
                    .CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE,
                HttpStatus.CONFLICT))
        .when(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion(anyString(), anyList());

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .satisfies(refusal(HttpStatus.CONFLICT, "TENANT_CHANGE_LAST_COUNSELLOR"));

    verifyNoInteractions(identityTenantUpdater);
    verify(auditRepository, never()).save(any());
  }

  @Test
  void failsWith503AndWritesNoAuditWhenTheIdentityProviderRejectsTheTenant() {
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());
    doThrow(new IllegalStateException("keycloak down"))
        .when(identityTenantUpdater)
        .updateTenant(CONSULTANT_ID, NEW_TENANT);

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .isInstanceOf(ServiceUnavailableException.class);

    verify(auditRepository, never()).save(any());
  }

  @Test
  void putsTheOldTenantBackIntoTheIdentityProviderWhenTheTransactionRollsBack() {
    TransactionSynchronizationManager.initSynchronization();
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());

    move(NEW_TENANT, 800L);
    verify(identityTenantUpdater).updateTenant(CONSULTANT_ID, NEW_TENANT);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    verify(identityTenantUpdater).updateTenant(CONSULTANT_ID, OLD_TENANT);
    verify(identityTenantUpdater, never()).endSessions(anyString());
  }

  @Test
  void endsTheLoginSessionsOnlyAfterTheMoveWasCommitted() {
    TransactionSynchronizationManager.initSynchronization();
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());

    move(NEW_TENANT, 800L);
    verify(identityTenantUpdater, never()).endSessions(anyString());

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    verify(identityTenantUpdater).endSessions(CONSULTANT_ID);
  }

  @Test
  void aFailingSessionEndAfterTheCommitDoesNotUndoTheMove() {
    TransactionSynchronizationManager.initSynchronization();
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());
    doThrow(new IllegalStateException("keycloak down"))
        .when(identityTenantUpdater)
        .endSessions(CONSULTANT_ID);

    move(NEW_TENANT, 800L);

    // The tenant is already switched, so the old tokens cannot read the old Träger anymore: the
    // move stays done and the failure is only logged.
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
  }

  @Test
  void aCentreWithOpenEnquiriesThatLosesItsLastCounsellorIsTheSameReason() {
    found();
    oldAgencies(700L);
    when(topicAgencyValidator.validateGrantTopicsAgainstSelectedAgencies(any(), any(), anyLong()))
        .thenReturn(Map.of());
    doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason
                    .CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES,
                HttpStatus.CONFLICT))
        .when(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion(anyString(), anyList());

    assertThatThrownBy(() -> move(NEW_TENANT, 800L))
        .satisfies(refusal(HttpStatus.CONFLICT, "TENANT_CHANGE_LAST_COUNSELLOR"));
  }

  // --- helpers ---------------------------------------------------------------------------

  /** A refusal the Admin can map: the status and a stable X-Reason code. */
  private static org.assertj.core.api.ThrowingConsumer<Throwable> refusal(
      HttpStatus status, String reason) {
    return thrown -> {
      assertThat(thrown).isInstanceOf(CustomValidationHttpStatusException.class);
      var refusal = (CustomValidationHttpStatusException) thrown;
      assertThat(refusal.getHttpStatus()).isEqualTo(status);
      assertThat(refusal.getCustomHttpHeaders().getFirst("X-Reason")).isEqualTo(reason);
    };
  }

  private ConsultantTenantChangeService.Result move(long tenantId, long agencyId) {
    return service.changeTenant(CONSULTANT_ID, tenantId, List.of(agency(agencyId)), null, ACTOR_ID);
  }

  private void found() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));
  }

  private void oldAgencies(Long... agencyIds) {
    var relations =
        java.util.Arrays.stream(agencyIds)
            .map(id -> ConsultantAgency.builder().consultant(consultant).agencyId(id).build())
            .toList();
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(relations);
  }

  private static CreateConsultantAgencyDTO agency(long id) {
    return new CreateConsultantAgencyDTO().agencyId(id).roleSetKey("consultant");
  }

  private Recorder createdRelationsRecorder() {
    var recorder = new Recorder();
    doAnswer(
            invocation -> {
              ConsultantAgencyCreationInput input = invocation.getArgument(0);
              recorder.tenantAtCreation.add(consultant.getTenantId());
              recorder.additionalAgencyIds.add(List.copyOf(input.getAdditionalAgencyIds()));
              return null;
            })
        .when(relationCreatorService)
        .prepareConsultantAgencyRelation(any());
    return recorder;
  }

  private static final class Recorder {
    final java.util.List<Long> tenantAtCreation = new java.util.ArrayList<>();
    final java.util.List<List<Long>> additionalAgencyIds = new java.util.ArrayList<>();
  }
}
