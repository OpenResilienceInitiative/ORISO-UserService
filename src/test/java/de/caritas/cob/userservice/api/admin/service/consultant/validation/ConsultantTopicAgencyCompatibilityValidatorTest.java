package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
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
            List.of(
                agency(10L, 1L, false, List.of(3L)),
                agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateGrantTopicsAgainstSelectedAgencies(
                List.of(3L, 7L), List.of(10L, 20L), 1L));
  }

  @Test
  void validateGrantTopicsAgainstSelectedAgencies_RejectsTopicsOnlyCoveredByOfflineAgency() {
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(
                agency(10L, 1L, false, List.of(3L)),
                agency(20L, 1L, true, List.of(7L))));

    var exception =
        assertThrows(
            BadRequestException.class,
            () ->
                validator.validateGrantTopicsAgainstSelectedAgencies(
                    List.of(7L), List.of(10L, 20L), 1L));

    assertTrue(exception.getMessage().contains("[7]"));
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
  void validateCurrentTopicsAgainstSelectedAgencies_UsesPersistedConsultantTopics() {
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant-id"))
        .thenReturn(Optional.of(consultant("consultant-id", 1L)));
    when(consultantTopicRepository.findTopicIdsByConsultantId("consultant-id"))
        .thenReturn(List.of(3L, 7L));
    when(agencyService.getAgenciesWithoutCaching(List.of(10L, 20L)))
        .thenReturn(
            List.of(
                agency(10L, 1L, false, List.of(3L)),
                agency(20L, 1L, false, List.of(7L))));

    assertDoesNotThrow(
        () ->
            validator.validateCurrentTopicsAgainstSelectedAgencies(
                "consultant-id", List.of(10L, 20L)));
  }

  private AgencyDTO agency(Long agencyId, Long tenantId, boolean offline, List<Long> topicIds) {
    return new AgencyDTO()
        .id(agencyId)
        .tenantId(tenantId)
        .offline(offline)
        .topicIds(topicIds);
  }

  private Consultant consultant(String consultantId, Long tenantId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    consultant.setTenantId(tenantId);
    return consultant;
  }
}
