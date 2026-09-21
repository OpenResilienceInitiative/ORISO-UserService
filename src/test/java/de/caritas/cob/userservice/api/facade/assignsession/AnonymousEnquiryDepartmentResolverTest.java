package de.caritas.cob.userservice.api.facade.assignsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-022 decision 1 / ADR-003: accepting a topic-based anonymous enquiry must bind the accepting
 * counsellor's department (agency x topic), so the entry room can show that centre's consent text.
 */
@ExtendWith(MockitoExtension.class)
class AnonymousEnquiryDepartmentResolverTest {

  private static final long TOPIC_ID = 20L;
  private static final long SESSION_TENANT_ID = 1L;

  @InjectMocks AnonymousEnquiryDepartmentResolver resolver;
  @Mock AgencyService agencyService;

  @Test
  void resolveAgencyId_Should_returnTheOnlyAgencyOfTheConsultantThatOffersTheTopic() {
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenReturn(
            List.of(
                agency(10L, SESSION_TENANT_ID, 7L), agency(11L, SESSION_TENANT_ID, TOPIC_ID, 8L)));

    var agencyId = resolver.resolveAgencyId(unboundSession(), consultantInAgencies(10L, 11L));

    assertThat(agencyId).contains(11L);
  }

  @Test
  void resolveAgencyId_Should_preferAnAgencyInTheSessionsTenant_When_severalOfferTheTopic() {
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenReturn(List.of(agency(5L, 2L, TOPIC_ID), agency(30L, SESSION_TENANT_ID, TOPIC_ID)));

    var agencyId = resolver.resolveAgencyId(unboundSession(), consultantInAgencies(5L, 30L));

    assertThat(agencyId).contains(30L);
  }

  @Test
  void resolveAgencyId_Should_pickTheLowestId_When_severalInTheSameTenantOfferTheTopic() {
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenReturn(
            List.of(
                agency(42L, SESSION_TENANT_ID, TOPIC_ID),
                agency(17L, SESSION_TENANT_ID, TOPIC_ID)));

    var agencyId = resolver.resolveAgencyId(unboundSession(), consultantInAgencies(42L, 17L));

    assertThat(agencyId).contains(17L);
  }

  @Test
  void resolveAgencyId_Should_ignoreAgencyMembershipsThatWereDeleted() {
    var consultant = consultantInAgencies(12L);
    consultant
        .getConsultantAgencies()
        .add(
            ConsultantAgency.builder()
                .id(1003L)
                .agencyId(3L)
                .consultant(consultant)
                .deleteDate(LocalDateTime.now())
                .build());
    when(agencyService.getAgenciesWithoutCaching(List.of(12L)))
        .thenReturn(List.of(agency(12L, SESSION_TENANT_ID, TOPIC_ID)));

    assertThat(resolver.resolveAgencyId(unboundSession(), consultant)).contains(12L);
  }

  @Test
  void resolveAgencyId_Should_returnEmpty_When_noneOfTheConsultantsAgenciesOffersTheTopic() {
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenReturn(List.of(agency(10L, SESSION_TENANT_ID, 7L)));

    assertThat(resolver.resolveAgencyId(unboundSession(), consultantInAgencies(10L))).isEmpty();
  }

  @Test
  void resolveAgencyId_Should_failRetryably_When_agencyServiceIsUnavailable() {
    // An outage is not "no department": answering empty would let the accept persist the enquiry
    // IN_PROGRESS without one, and the in-progress check then blocks every retry for good.
    when(agencyService.getAgenciesWithoutCaching(any()))
        .thenThrow(new IllegalStateException("agency service down"));

    assertThatThrownBy(() -> resolver.resolveAgencyId(unboundSession(), consultantInAgencies(10L)))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void resolveAgencyId_Should_returnEmpty_When_theSessionHasNoMainTopic() {
    var session = unboundSession();
    session.setMainTopicId(null);

    assertThat(resolver.resolveAgencyId(session, consultantInAgencies(10L))).isEmpty();
    verifyNoInteractions(agencyService);
  }

  @Test
  void resolveAgencyId_Should_returnEmpty_When_theConsultantHasNoAgency() {
    var consultant = consultant();

    assertThat(resolver.resolveAgencyId(unboundSession(), consultant)).isEmpty();
    verifyNoInteractions(agencyService);
  }

  @Test
  void resolveAgencyId_Should_returnEmpty_When_theSessionAlreadyHasAnAgency() {
    var session = unboundSession();
    session.setAgencyId(99L);

    assertThat(resolver.resolveAgencyId(session, consultantInAgencies(10L))).isEmpty();
    verifyNoInteractions(agencyService);
  }

  private static Session unboundSession() {
    return Session.builder()
        .id(1L)
        .registrationType(RegistrationType.ANONYMOUS)
        .postcode("00000")
        .status(Session.SessionStatus.NEW)
        .teamSession(false)
        .tenantId(SESSION_TENANT_ID)
        .mainTopicId(TOPIC_ID)
        .build();
  }

  private static Consultant consultant() {
    return Consultant.builder()
        .id("consultant-id")
        .username("consultant")
        .firstName("first")
        .lastName("last")
        .email("consultant@example.org")
        .build();
  }

  private static Consultant consultantInAgencies(Long... agencyIds) {
    var consultant = consultant();
    Set<ConsultantAgency> agencies =
        Arrays.stream(agencyIds)
            .map(
                id ->
                    ConsultantAgency.builder()
                        .id(1000 + id)
                        .agencyId(id)
                        .consultant(consultant)
                        .build())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    consultant.setConsultantAgencies(agencies);
    return consultant;
  }

  private static AgencyDTO agency(Long id, Long tenantId, Long... topicIds) {
    return new AgencyDTO().id(id).tenantId(tenantId).topicIds(List.of(topicIds));
  }
}
