package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.delete.ConsultantPreDeletionService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class ConsultantAdminServiceTest {

  @InjectMocks private ConsultantAdminService consultantAdminService;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private CreateConsultantSaga createConsultantSaga;

  @Mock private ConsultantUpdateService consultantUpdateService;

  @Mock private ConsultantPreDeletionService consultantPreDeletionService;

  @Mock private AppointmentService appointmentService;

  @Mock private SessionRepository sessionRepository;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private AccountManager accountManager;

  @Mock private DeletionLifecycleService deletionLifecycleService;

  @Mock private ConsultantTopicRepository consultantTopicRepository;

  @Mock private TopicService topicService;

  @Test
  public void createNewConsultant_Should_enrichResponseWithTopicNames() {
    var embedded = new ConsultantDTO().id("c1");
    var response = new ConsultantAdminResponseDTO().embedded(embedded);
    when(this.createConsultantSaga.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(response);
    when(this.consultantTopicRepository.findTopicIdsByConsultantId("c1"))
        .thenReturn(List.of(3L, 7L));
    when(this.topicService.getAllTopicsMap())
        .thenReturn(
            Map.of(
                3L, new TopicDTO().id(3L).name("Addiction"),
                7L, new TopicDTO().id(7L).name("Family")));

    var result = this.consultantAdminService.createNewConsultant(mock(CreateConsultantDTO.class));

    assertEquals(2, result.getEmbedded().getTopics().size());
    assertEquals(
        Set.of("Addiction", "Family"),
        result.getEmbedded().getTopics().stream()
            .map(t -> t.getName())
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  public void createNewConsultant_Should_returnEmptyTopics_When_consultantHasNoTopics() {
    var response = new ConsultantAdminResponseDTO().embedded(new ConsultantDTO().id("c1"));
    when(this.createConsultantSaga.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(response);
    when(this.consultantTopicRepository.findTopicIdsByConsultantId("c1")).thenReturn(List.of());

    var result = this.consultantAdminService.createNewConsultant(mock(CreateConsultantDTO.class));

    assertEquals(0, result.getEmbedded().getTopics().size());
  }

  @Test
  public void
      markConsultantForDeletion_Should_throwNotFoundException_When_consultantdoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> {
          when(this.consultantRepository.findByIdAndDeleteDateIsNull(any()))
              .thenReturn(Optional.empty());

          this.consultantAdminService.markConsultantForDeletion("id", false);
        });
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-02
  // ---------------------------------------------------------------------------

  @Test
  void findConsultantById_Should_ThrowNoContent_When_NotFound() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1")).thenReturn(Optional.empty());
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NoContentException.class,
        () -> consultantAdminService.findConsultantById("c-1"));
  }

  @Test
  void findConsultantById_Should_ReturnDTO_When_FoundWithDisplayName() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    when(consultantTopicRepository.findTopicIdsByConsultantId("c-1")).thenReturn(List.of());
    when(accountManager.findConsultant("c-1"))
        .thenReturn(Optional.of(Map.of("displayName", "Test User")));

    var result = consultantAdminService.findConsultantById("c-1");

    assertThat(result.getEmbedded().getDisplayName()).isEqualTo("Test User");
  }

  @Test
  void findConsultantById_Should_ReturnDTO_When_FoundWithoutDisplayName() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    when(consultantTopicRepository.findTopicIdsByConsultantId("c-1")).thenReturn(List.of());
    when(accountManager.findConsultant("c-1")).thenReturn(Optional.of(Map.of("id", "c-1")));

    var result = consultantAdminService.findConsultantById("c-1");

    assertThat(result.getEmbedded().getDisplayName()).isNull();
  }

  @Test
  void updateConsultant_Should_ReturnDTO_When_Updated() {
    var updated = new Consultant();
    updated.setId("c-1");
    var dto = new de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO();
    when(consultantUpdateService.updateConsultant("c-1", dto)).thenReturn(updated);
    when(consultantTopicRepository.findTopicIdsByConsultantId("c-1")).thenReturn(List.of());

    var result = consultantAdminService.updateConsultant("c-1", dto);

    assertThat(result).isNotNull();
    verify(appointmentService).updateConsultant(result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void markConsultantForDeletion_Should_DeleteSessions_When_ForceIsTrue() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    var session = new de.caritas.cob.userservice.api.model.Session();
    when(consultantRepository.findByIdAndDeleteDateIsNull("c-1"))
        .thenReturn(Optional.of(consultant));
    when(sessionRepository.findByConsultantAndStatusIn(any(Consultant.class), any(List.class)))
        .thenReturn(List.of(session))
        .thenReturn(List.of());

    consultantAdminService.markConsultantForDeletion("c-1", true);

    var deletionOrder = inOrder(caseHandoverRequestRepository, sessionRepository);
    deletionOrder.verify(caseHandoverRequestRepository).deleteAllBySessionId(session.getId());
    deletionOrder.verify(sessionRepository).delete(session);
    verify(deletionLifecycleService).beginConsultantDeletion(any(), any());
  }

  @Test
  void pauseConsultantDeletion_Should_ThrowNotFound_When_ConsultantNotFound() {
    when(consultantRepository.findById("c-1")).thenReturn(Optional.empty());
    assertThrows(
        NotFoundException.class,
        () -> consultantAdminService.pauseConsultantDeletion("c-1", "reason", 3, "admin"));
  }

  @Test
  void pauseConsultantDeletion_Should_ThrowNotFound_When_ConsultantNotMarkedForDeletion() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setDeleteDate(null);
    when(consultantRepository.findById("c-1")).thenReturn(Optional.of(consultant));
    assertThrows(
        NotFoundException.class,
        () -> consultantAdminService.pauseConsultantDeletion("c-1", "reason", 3, "admin"));
  }

  @Test
  void pauseConsultantDeletion_Should_Pause_When_ValidConsultantMarkedForDeletion() {
    var consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setDeleteDate(java.time.LocalDateTime.now());
    when(consultantRepository.findById("c-1")).thenReturn(Optional.of(consultant));

    consultantAdminService.pauseConsultantDeletion("c-1", "reason", 3, "admin");

    verify(deletionLifecycleService).pauseConsultantDeletion(consultant, "reason", 3, "admin");
    verify(consultantRepository).save(consultant);
  }

  @Test
  public void
      markConsultantForDeletion_Should_executePreDeletionStepsAndMarkConsultantAsDeleted_When_consultantExists() {
    Consultant consultant = mock(Consultant.class);
    when(this.consultantRepository.findByIdAndDeleteDateIsNull(any()))
        .thenReturn(Optional.of(consultant));

    this.consultantAdminService.markConsultantForDeletion("id", false);

    verify(this.consultantPreDeletionService, times(1)).performPreDeletionSteps(consultant, false);
    verify(this.deletionLifecycleService, times(1)).beginConsultantDeletion(any(), any());
    verify(consultant, times(1)).setStatus(ConsultantStatus.IN_DELETION);
    verify(this.consultantRepository, times(1)).save(consultant);
  }
}
