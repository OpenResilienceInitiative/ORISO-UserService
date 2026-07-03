package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Optional;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class ConsultantAgencyRelationCreatorServiceTest {

  private final EasyRandom easyRandom = new EasyRandom();

  @InjectMocks
  private ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;

  @Mock private ConsultantAgencyService consultantAgencyService;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private AgencyService agencyService;

  @Mock private KeycloakService keycloakService;

  @Mock private RocketChatAsyncHelper rocketChatAsyncHelper;

  @Mock private ConsultingTypeManager consultingTypeManager;

  @Mock
  private ConsultantTopicAgencyCompatibilityValidator consultantTopicAgencyCompatibilityValidator;

  @Test
  public void
      createNewConsultantAgency_Should_notThrowNullPointerException_When_agencyTypeIsU25AndConsultantHasNoAgencyAssigned() {
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(2L);

    when(this.consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(new Consultant()));
    when(agencyService.getAgencyWithoutCaching(eq(2L))).thenReturn(agencyDTO);

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

    final var response =
        easyRandom.nextObject(
            de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                .ExtendedConsultingTypeResponseDTO.class);
    when(consultingTypeManager.getConsultingTypeSettings(1)).thenReturn(response);

    assertDoesNotThrow(
        () ->
            this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", createConsultantAgencyDTO));
  }

  @Test
  public void
      completeConsultantAgencyAssigment_Should_finalizeSynchronously_When_rocketChatDisabled() {
    ReflectionTestUtils.setField(
        consultantAgencyRelationCreatorService, "rocketChatEnabled", false);

    var consultant = new Consultant();
    consultant.setStatus(ConsultantStatus.CREATED);
    var agencyDTO = new AgencyDTO().id(2L).teamAgency(false);

    when(consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgencyWithoutCaching(eq(2L))).thenReturn(agencyDTO);

    var input =
        new CreateConsultantAgencyDTOInputAdapter(
            "consultant Id", new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAgencyRelationCreatorService.completeConsultantAgencyAssigment(
        input, LogService::logInfo);

    // Matrix-only path: status is finalized synchronously in the caller's transaction, not via the
    // Rocket.Chat async assignment.
    verify(rocketChatAsyncHelper).finalizeConsultantAgencyRelation(consultant, agencyDTO);
    verify(rocketChatAsyncHelper, never()).addConsultantToSessions(any(), any(), any(), any());
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.IN_PROGRESS);
    verify(consultantRepository).save(consultant);
  }

  @Test
  public void createNewConsultantAgency_Should_notSaveRelation_When_topicAgencyValidationFails() {
    var consultant = new Consultant();
    consultant.setId("consultant Id");
    consultant.setTenantId(1L);
    AgencyDTO agencyDTO = new AgencyDTO().consultingType(1).id(2L);

    when(this.consultantRepository.findByIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.of(consultant));
    when(agencyService.getAgencyWithoutCaching(eq(2L))).thenReturn(agencyDTO);
    doThrow(new BadRequestException("topic not covered"))
        .when(consultantTopicAgencyCompatibilityValidator)
        .validateCurrentTopicsAgainstAssignedAndAdditionalAgencies("consultant Id", Set.of(2L), 1L);

    CreateConsultantAgencyDTO createConsultantAgencyDTO =
        new CreateConsultantAgencyDTO().roleSetKey("valid role set").agencyId(2L);

    assertThrows(
        BadRequestException.class,
        () ->
            this.consultantAgencyRelationCreatorService.createNewConsultantAgency(
                "consultant Id", createConsultantAgencyDTO));

    verify(consultantAgencyService, never()).saveConsultantAgency(any(ConsultantAgency.class));
  }
}
