package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsultantAgencyAdminServiceTest {

  @InjectMocks private ConsultantAgencyAdminService consultantAgencyAdminService;

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private RemoveConsultantFromSessionRoomsService removeFromSessionRoomsService;
  @Mock private AgencyService agencyService;
  @Mock private AgencyAdminService agencyAdminService;
  @Mock private ConsultantAgencyDeletionValidationService agencyDeletionValidationService;

  // ---------------------------------------------------------------------------
  // findConsultantAgencies
  // ---------------------------------------------------------------------------

  @Test
  void findConsultantAgencies_Should_ThrowBadRequest_When_ConsultantNotFound() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1")).thenReturn(Optional.empty());

    assertThrows(
        BadRequestException.class,
        () -> consultantAgencyAdminService.findConsultantAgencies("c-1"));
  }

  @Test
  void findConsultantAgencies_Should_ReturnResponseDTO_When_ConsultantFound() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull("c-1"))
        .thenReturn(List.of());
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of());

    var result = consultantAgencyAdminService.findConsultantAgencies("c-1");

    assertThat(result).isNotNull();
    assertThat(result.getEmbedded()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // appendAgenciesForConsultants
  // ---------------------------------------------------------------------------

  @Test
  void appendAgenciesForConsultants_Should_SetEmptyAgencies_When_NoAgenciesExist() {
    var dto = new ConsultantDTO();
    dto.setId("c-1");
    when(consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(Set.of("c-1")))
        .thenReturn(List.of());
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of());

    consultantAgencyAdminService.appendAgenciesForConsultants(Set.of(dto));

    assertThat(dto.getAgencies()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // markAllAssignedConsultantsAsTeamConsultant
  // ---------------------------------------------------------------------------

  @Test
  void markAllAssignedConsultantsAsTeamConsultant_Should_MarkNonTeamConsultants() {
    var consultant = new Consultant();
    consultant.setTeamConsultant(false);
    var relation = new ConsultantAgency();
    relation.setConsultant(consultant);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(10L))
        .thenReturn(List.of(relation));

    consultantAgencyAdminService.markAllAssignedConsultantsAsTeamConsultant(10L);

    assertThat(consultant.isTeamConsultant()).isTrue();
    verify(consultantRepository).save(consultant);
  }

  @Test
  void markAllAssignedConsultantsAsTeamConsultant_Should_SkipAlreadyTeamConsultants() {
    var consultant = new Consultant();
    consultant.setTeamConsultant(true);
    var relation = new ConsultantAgency();
    relation.setConsultant(consultant);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(10L))
        .thenReturn(List.of(relation));

    consultantAgencyAdminService.markAllAssignedConsultantsAsTeamConsultant(10L);

    verify(consultantRepository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // removeConsultantsFromTeamSessionsByAgencyId
  // ---------------------------------------------------------------------------

  @Test
  void removeConsultantsFromTeamSessionsByAgencyId_Should_ChangeSessionsToNonTeam() {
    var session = new Session();
    session.setTeamSession(true);
    when(sessionRepository.findByAgencyIdAndStatusAndTeamSessionIsTrue(
            10L, SessionStatus.IN_PROGRESS))
        .thenReturn(List.of(session));
    when(consultantRepository.findByConsultantAgenciesAgencyIdInAndDeleteDateIsNull(List.of(10L)))
        .thenReturn(List.of());

    consultantAgencyAdminService.removeConsultantsFromTeamSessionsByAgencyId(10L);

    assertThat(session.isTeamSession()).isFalse();
    verify(sessionRepository).save(session);
    verify(removeFromSessionRoomsService).removeConsultantFromSessions(List.of(session));
  }

  @Test
  void removeConsultantsFromTeamSessionsByAgencyId_Should_RemoveTeamFlag_When_NoOtherTeamAgency() {
    var teamAgency = new AgencyDTO();
    teamAgency.setId(10L);
    teamAgency.setTeamAgency(true);

    var consultantAgency = new ConsultantAgency();
    consultantAgency.setAgencyId(10L);

    var consultant = new Consultant();
    consultant.setTeamConsultant(true);
    consultant.setConsultantAgencies(new java.util.HashSet<>(Set.of(consultantAgency)));

    when(sessionRepository.findByAgencyIdAndStatusAndTeamSessionIsTrue(
            10L, SessionStatus.IN_PROGRESS))
        .thenReturn(List.of());
    when(consultantRepository.findByConsultantAgenciesAgencyIdInAndDeleteDateIsNull(List.of(10L)))
        .thenReturn(List.of(consultant));
    // The only agency is 10L (the one being removed), so no other team agency remains
    // noOtherTeamAgency filters out agencyId == 10L, leaving nothing — noneMatch returns true
    // agencyService.getAgency is never called because the stream filters it out
    when(agencyService.getAgency(10L)).thenReturn(teamAgency);

    consultantAgencyAdminService.removeConsultantsFromTeamSessionsByAgencyId(10L);

    assertThat(consultant.isTeamConsultant()).isFalse();
    verify(consultantRepository).save(consultant);
  }

  // ---------------------------------------------------------------------------
  // markConsultantAgencyForDeletion
  // ---------------------------------------------------------------------------

  @Test
  void markConsultantAgencyForDeletion_Should_ThrowException_When_RelationNotFound() {
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull("c-1", 10L))
        .thenReturn(List.of());

    assertThrows(
        CustomValidationHttpStatusException.class,
        () -> consultantAgencyAdminService.markConsultantAgencyForDeletion("c-1", 10L));
  }

  @Test
  void markConsultantAgencyForDeletion_Should_MarkAsDeleted_When_RelationFound() {
    var relation = new ConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull("c-1", 10L))
        .thenReturn(List.of(relation));

    consultantAgencyAdminService.markConsultantAgencyForDeletion("c-1", 10L);

    verify(agencyDeletionValidationService).validateAndMarkForDeletion(relation);
    verify(consultantAgencyRepository).save(relation);
    assertThat(relation.getDeleteDate()).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // markConsultantAgenciesForDeletion
  // ---------------------------------------------------------------------------

  @Test
  void markConsultantAgenciesForDeletion_Should_MarkAllRelationsAsDeleted() {
    var relation1 = new ConsultantAgency();
    var relation2 = new ConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull("c-1", 10L))
        .thenReturn(List.of(relation1));
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndDeleteDateIsNull("c-1", 20L))
        .thenReturn(List.of(relation2));

    consultantAgencyAdminService.markConsultantAgenciesForDeletion("c-1", List.of(10L, 20L));

    verify(consultantAgencyRepository, org.mockito.Mockito.times(2))
        .save(any(ConsultantAgency.class));
    assertThat(relation1.getDeleteDate()).isNotNull();
    assertThat(relation2.getDeleteDate()).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // findConsultantsForAgency
  // ---------------------------------------------------------------------------

  @Test
  void findConsultantsForAgency_Should_ReturnResponseDTO_With_AllConsultants() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    var relation = new ConsultantAgency();
    relation.setConsultant(consultant);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(10L))
        .thenReturn(List.of(relation));

    var result = consultantAgencyAdminService.findConsultantsForAgency(10L);

    assertThat(result).isNotNull();
    assertThat(result.getEmbedded()).hasSize(1);
  }
}
