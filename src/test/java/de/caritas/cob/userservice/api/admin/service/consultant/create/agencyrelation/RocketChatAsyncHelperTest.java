package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.helper.MailService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RocketChatAsyncHelperTest {

  @InjectMocks private RocketChatAsyncHelper rocketChatAsyncHelper;

  @Mock private RocketChatFacade rocketChatFacade;
  @Mock private SessionRepository sessionRepository;
  @Mock private IdentityClient identityClient;
  @Mock private ConsultingTypeManager consultingTypeManager;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private MailService mailService;

  private Consultant givenConsultant(String matrixUserId) {
    Consultant consultant = new Consultant();
    consultant.setId("consultantId");
    consultant.setUsername("consultantUsername");
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }

  private AgencyDTO givenAgency(boolean teamAgency) {
    AgencyDTO agency = new AgencyDTO();
    agency.setId(1L);
    agency.setTeamAgency(teamAgency);
    return agency;
  }

  private ConsultantAgency givenInProgressConsultantAgency() {
    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setStatus(ConsultantAgencyStatus.IN_PROGRESS);
    return consultantAgency;
  }

  @Test
  void
      addConsultantToSessions_Should_SetConsultantCreated_When_NoRelevantSessionsAndNoOtherInProgressRelations() {
    ReflectionTestUtils.setField(rocketChatAsyncHelper, "applicationBaseUrl", "http://base.url");
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(false);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(1L, SessionStatus.NEW))
        .thenReturn(List.of());
    ConsultantAgency consultantAgency = givenInProgressConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            "consultantId", 1L, ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(consultantAgency);
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            "consultantId", ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of());

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    assertThat(consultantAgency.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    verify(consultantAgencyRepository).save(consultantAgency);
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
    verify(consultantRepository).save(consultant);
  }

  @Test
  void addConsultantToSessions_Should_IncludeTeamSessions_When_AgencyIsTeamAgency() {
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(true);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(1L, SessionStatus.NEW))
        .thenReturn(List.of());
    when(sessionRepository.findByAgencyIdAndStatusAndTeamSessionIsTrue(
            1L, SessionStatus.IN_PROGRESS))
        .thenReturn(List.of());
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            any(), any(), any()))
        .thenReturn(givenInProgressConsultantAgency());
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(any(), any()))
        .thenReturn(List.of());

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    verify(sessionRepository)
        .findByAgencyIdAndStatusAndTeamSessionIsTrue(1L, SessionStatus.IN_PROGRESS);
  }

  @Test
  void
      addConsultantToSessions_Should_SetErrorStatusAndSendMail_When_ExceptionThrownAndConsultantHasNoMatrixId() {
    ReflectionTestUtils.setField(rocketChatAsyncHelper, "applicationBaseUrl", "http://base.url");
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(false);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(any(), any()))
        .thenThrow(new RuntimeException("db down"));

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.ERROR);
    verify(consultantRepository).save(consultant);
    verify(mailService).sendErrorEmailNotification(any());
  }

  @Test
  void
      addConsultantToSessions_Should_ContinueAndFinalizeStatus_When_ExceptionThrownButConsultantHasMatrixId() {
    Consultant consultant = givenConsultant("@matrixUser:matrix.oriso.org");
    AgencyDTO agency = givenAgency(false);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(any(), any()))
        .thenThrow(new RuntimeException("rc down"));
    ConsultantAgency consultantAgency = givenInProgressConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            any(), any(), any()))
        .thenReturn(consultantAgency);
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(any(), any()))
        .thenReturn(List.of());

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    verify(mailService, never()).sendErrorEmailNotification(any());
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
    verify(consultantAgencyRepository, times(1)).save(consultantAgency);
  }

  @Test
  void addConsultantToSessions_Should_SkipStatusFinalization_When_NoInProgressRelationVisible() {
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(false);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(any(), any()))
        .thenReturn(List.of());
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            any(), any(), any()))
        .thenReturn(null);

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    verify(consultantAgencyRepository, never()).save(any());
    verify(consultantRepository, never()).save(any());
  }

  @Test
  void
      addConsultantToSessions_Should_NotSetConsultantCreated_When_OtherInProgressRelationsStillExist() {
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(false);
    when(sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(any(), any()))
        .thenReturn(List.of());
    ConsultantAgency consultantAgency = givenInProgressConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            any(), any(), any()))
        .thenReturn(consultantAgency);
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(any(), any()))
        .thenReturn(List.of(givenInProgressConsultantAgency()));

    rocketChatAsyncHelper.addConsultantToSessions(consultant, agency, msg -> {}, 1L);

    verify(consultantAgencyRepository).save(consultantAgency);
    verify(consultantRepository, never()).save(any());
  }

  @Test
  void finalizeConsultantAgencyRelation_Should_UpdateConsultantStatus_When_RelationIsInProgress() {
    Consultant consultant = givenConsultant(null);
    AgencyDTO agency = givenAgency(false);
    ConsultantAgency consultantAgency = givenInProgressConsultantAgency();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            "consultantId", 1L, ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(consultantAgency);
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            "consultantId", ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of());

    rocketChatAsyncHelper.finalizeConsultantAgencyRelation(consultant, agency);

    assertThat(consultantAgency.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
    verify(consultantRepository).save(consultant);
  }
}
