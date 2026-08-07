package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantTopicAgencyCompatibilityValidatorTest {

  @InjectMocks private ConsultantTopicAgencyCompatibilityValidator validator;

  @Mock private AgencyService agencyService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantTopicRepository consultantTopicRepository;

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_AllowsTopicsCoveredAcrossActiveAgencies() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(agency(10L, 1L, false, List.of(3L)), agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateGrantTopicsAgainstSelectedAgencies(
                List.of(3L, 7L), List.of(10L, 20L), 1L));
  }

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_AllowsTopicsCoveredByOfflineAgency() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(agency(10L, 1L, false, List.of(3L)), agency(20L, 1L, true, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateGrantTopicsAgainstSelectedAgencies(
                List.of(7L), List.of(10L, 20L), 1L));
  }

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_RejectsAgencyFromDifferentTenant() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L)))
        .thenReturn(List.of(agency(10L, 2L, false, List.of(3L))));

    var exception =
        assertThrows(
            BadRequestException.class,
            () ->
                validator.validateGrantTopicsAgainstSelectedAgencies(
                    List.of(3L), List.of(10L), 1L));

    assertTrue(exception.getMessage().contains("tenant 1"));
  }

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_RejectsForeignAgencyWithoutTopics() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L)))
        .thenReturn(List.of(agency(10L, 2L, false, List.of())));

    var exception =
        assertThrows(
            BadRequestException.class,
            () ->
                validator.validateGrantTopicsAgainstSelectedAgencies(List.of(), List.of(10L), 1L));

    assertTrue(exception.getMessage().contains("tenant 1"));
  }

  @Test
  void validateCurrentTopicsAgainstSelectedAgencies_UsesPersistedConsultantTopics() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant-id"))
        .thenReturn(Optional.of(consultant("consultant-id", 1L)));
    when(consultantTopicRepository.findTopicIdsByConsultantId("consultant-id"))
        .thenReturn(List.of(3L, 7L));
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(agency(10L, 1L, false, List.of(3L)), agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateCurrentTopicsAgainstSelectedAgencies(
                "consultant-id", List.of(10L, 20L)));
  }

  @Test
  void
      validateCurrentTopicsAgainstAssignedAndAdditionalAgencies_AllowsTopicsCoveredByCombinedGrant() {
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull("consultant-id"))
        .thenReturn(List.of());
    when(consultantTopicRepository.findTopicIdsByConsultantId("consultant-id"))
        .thenReturn(List.of(3L, 7L));
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(agency(10L, 1L, false, List.of(3L)), agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
                "consultant-id", List.of(10L, 20L), 1L));
  }

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_ReadsAgenciesUncached() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(agency(10L, 1L, false, List.of(3L)), agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateGrantTopicsAgainstSelectedAgencies(
                List.of(3L, 7L), List.of(10L, 20L), 1L));

    verify(agencyService).getAgenciesWithoutCaching(List.of(10L, 20L));
    verify(agencyService, never()).getAgencies(anyList());
  }

  @Test
  void validateTopicUpdateAgainstAssignedAgencies_AcceptsTopicAddedToAssignedAgencyAfterCreation() {
    // Issue #939: the agency was created without topics and topic 3 was added afterwards. Reading
    // the assigned agency through the three-hour agencyCache kept serving the pre-topic snapshot,
    // so assigning topic 3 to the consultant was rejected although the agency covers it.
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull("consultant-id"))
        .thenReturn(List.of(consultantAgency(10L)));
    when(agencyService.getAgenciesWithoutCaching(List.of(10L)))
        .thenReturn(List.of(agency(10L, 1L, true, List.of(3L))));

    assertDoesNotThrow(
        () ->
            validator.validateTopicUpdateAgainstAssignedAgencies("consultant-id", List.of(3L), 1L));

    verify(agencyService, never()).getAgencies(anyList());
  }

  @Test
  void validateTopicUpdateAgainstAssignedAgencies_NamesTheEvaluatedAgencyCoverageOnRejection() {
    when(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull("consultant-id"))
        .thenReturn(List.of(consultantAgency(10L)));
    when(agencyService.getAgenciesWithoutCaching(List.of(10L)))
        .thenReturn(List.of(agency(10L, 1L, false, List.of(7L))));

    var exception =
        assertThrows(
            BadRequestException.class,
            () ->
                validator.validateTopicUpdateAgainstAssignedAgencies(
                    "consultant-id", List.of(3L), 1L));

    assertTrue(exception.getMessage().contains("topic ids [3]"));
    assertTrue(exception.getMessage().contains("coverage: {10=[7]}"));
  }

  private ConsultantAgency consultantAgency(Long agencyId) {
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setAgencyId(agencyId);
    return consultantAgency;
  }

  private AgencyDTO agency(Long agencyId, Long tenantId, boolean offline, List<Long> topicIds) {
    return new AgencyDTO().id(agencyId).tenantId(tenantId).offline(offline).topicIds(topicIds);
  }

  private Consultant consultant(String consultantId, Long tenantId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    consultant.setTenantId(tenantId);
    return consultant;
  }
}
