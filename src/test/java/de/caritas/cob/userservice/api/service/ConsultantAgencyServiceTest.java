package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
public class ConsultantAgencyServiceTest {

  private final String CONSULTANT_ID = "1b71cc46-650d-42bb-8299-f8e3f6d7249a";
  private final String CONSULTANT_MATRIX_USER_ID = "@consultant:matrix.example";
  private final Long AGENCY_ID = 1L;
  private final Consultant CONSULTANT =
      Consultant.builder()
          .id(CONSULTANT_ID)
          .matrixUserId(CONSULTANT_MATRIX_USER_ID)
          .username("consultant")
          .firstName("first name")
          .lastName("last name")
          .email("consultant@cob.de")
          .absent(false)
          .teamConsultant(false)
          .languageFormal(false)
          .status(ConsultantStatus.CREATED)
          .languageCode(LanguageCode.de)
          .build();
  private final ConsultantAgency CONSULTANT_AGENCY =
      new ConsultantAgency(
          AGENCY_ID, CONSULTANT, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
  private final List<ConsultantAgency> CONSULTANT_AGENCY_LIST = Arrays.asList(CONSULTANT_AGENCY);
  private final ConsultantAgency NULL_CONSULTANT_AGENCY = null;
  private final List<ConsultantAgency> CONSULTANT_AGENCY_NULL_LIST =
      Arrays.asList(NULL_CONSULTANT_AGENCY);
  private final ConsultantAgency CONSULTANT_NULL_AGENCY =
      new ConsultantAgency(AGENCY_ID, null, 1L, nowInUtc(), nowInUtc(), nowInUtc(), null, null);
  private final List<ConsultantAgency> CONSULTANT_NULL_AGENCY_LIST =
      Arrays.asList(CONSULTANT_NULL_AGENCY);

  @InjectMocks private ConsultantAgencyService consultantAgencyService;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private AgencyService agencyService;

  @Mock
  @SuppressWarnings("unused")
  private AccountManaging accountManager;

  @Mock private ConsultantTopicRepository consultantTopicRepository;
  @Mock private SessionRepository sessionRepository;

  @Mock
  @SuppressWarnings("unused")
  private UserDtoMapper userDtoMapper;

  @Test
  public void saveConsultantAgency_Should_SaveConsultantAgency() {

    consultantAgencyService.saveConsultantAgency(CONSULTANT_AGENCY);
    verify(consultantAgencyRepository, times(1)).save(Mockito.any());
  }

  /** Method: findConsultantsByAgencyId */
  @Test
  public void findConsultantsByAgencyId_Should_ReturnListOfConsultantAgency_WhenAgencyFound() {

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(Mockito.anyLong()))
        .thenReturn(CONSULTANT_AGENCY_LIST);

    assertThat(
        consultantAgencyService.findConsultantsByAgencyId(AGENCY_ID),
        everyItem(instanceOf(ConsultantAgency.class)));
  }

  @Test
  public void getLanguageCodesOfAgency_Should_BeReadOnlyTransactional() throws Exception {
    var method = ConsultantAgencyService.class.getMethod("getLanguageCodesOfAgency", long.class);
    var transactional = method.getAnnotation(Transactional.class);

    assertNotNull(transactional);
    assertTrue(transactional.readOnly());
  }

  /** Method: getConsultantsOfAgency */
  @Test
  public void
      getConsultantsOfAgency_Should_ThrowInternalServerErrorException_WhenDatabaseAgencyIsNull() {

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            Mockito.anyLong()))
        .thenReturn(CONSULTANT_AGENCY_NULL_LIST);

    try {
      consultantAgencyService.getConsultantsOfAgency(AGENCY_ID);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException serviceException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  public void
      getConsultantsOfAgency_Should_ThrowInternalServerErrorException_WhenDatabaseAgencyConsultantIsNull() {

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            Mockito.anyLong()))
        .thenReturn(CONSULTANT_NULL_AGENCY_LIST);

    try {
      consultantAgencyService.getConsultantsOfAgency(AGENCY_ID);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException serviceException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  public void getConsultantsOfAgency_Should_ReturnListOfConsultantAgency_WhenAgencyFound() {

    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            Mockito.anyLong()))
        .thenReturn(CONSULTANT_AGENCY_LIST);

    assertThat(
        consultantAgencyService.getConsultantsOfAgency(AGENCY_ID),
        everyItem(instanceOf(ConsultantResponseDTO.class)));
  }

  @Test
  public void getConsultantsOfAgency_Should_ReturnOnlyConsultantsNotMarkedAsDeleted() {
    var consultantAgencies =
        new EasyRandom().objects(ConsultantAgency.class, 10).collect(Collectors.toList());
    removeDeletionFlagForConsultantAtIndex(consultantAgencies, 0, 2, 4, 6, 8, 9);
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            any()))
        .thenReturn(consultantAgencies);

    var consultants = consultantAgencyService.getConsultantsOfAgency(0L);

    assertThat(consultants, hasSize(6));
  }

  private void removeDeletionFlagForConsultantAtIndex(
      List<ConsultantAgency> consultantAgencies, int... indexRange) {
    Arrays.stream(indexRange)
        .mapToObj(consultantAgencies::get)
        .map(ConsultantAgency::getConsultant)
        .forEach(consultant -> consultant.setDeleteDate(null));
  }

  @Test
  public void getOnlineAgenciesOfConsultant_Should_returnEmptyList_When_consultantDoesNotExist() {
    when(consultantAgencyRepository.findByConsultantId(any())).thenReturn(emptyList());

    var agencies = consultantAgencyService.getOnlineAgenciesOfConsultant("invalid");

    assertThat(agencies, hasSize(0));
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_returnLocalTopicAssignments_When_agencyServiceReturnsEmptyList() {
    var activeConsultantAgency =
        new ConsultantAgency(
            AGENCY_ID, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), null, 1L, null);
    when(consultantAgencyRepository.findByConsultantId("valid"))
        .thenReturn(singletonList(activeConsultantAgency));
    when(agencyService.getAgenciesNotCached(singletonList(AGENCY_ID))).thenReturn(emptyList());
    when(consultantTopicRepository.findTopicIdsByConsultantId("valid")).thenReturn(List.of(1L));
    when(sessionRepository.findLowestConsultingTypeIdsByAgencyIds(Set.of(AGENCY_ID)))
        .thenReturn(emptyList());
    ReflectionTestUtils.setField(
        consultantAgencyService, "registrationAgencyFallbackConsultingTypeId", 1);

    var agencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertThat(agencies, hasSize(1));
    assertEquals(AGENCY_ID, agencies.get(0).getId());
    assertEquals(List.of(1L), agencies.get(0).getTopicIds());
    assertEquals(Integer.valueOf(1), agencies.get(0).getConsultingType());
    verify(agencyService, Mockito.never()).getAgencyWithoutCaching(any());
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_enrichRemoteAgenciesWithConsultantTopicIds_When_topicIdsMissing() {
    var activeConsultantAgency =
        new ConsultantAgency(
            AGENCY_ID, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), null, 1L, null);
    when(consultantAgencyRepository.findByConsultantId("valid"))
        .thenReturn(singletonList(activeConsultantAgency));
    var remoteAgency = new AgencyDTO().id(AGENCY_ID).offline(false).consultingType(2);
    when(agencyService.getAgenciesNotCached(singletonList(AGENCY_ID)))
        .thenReturn(singletonList(remoteAgency));
    when(consultantTopicRepository.findTopicIdsByConsultantId("valid")).thenReturn(List.of(1L));

    var agencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertThat(agencies, hasSize(1));
    assertEquals(List.of(1L), agencies.get(0).getTopicIds());
    assertEquals(Integer.valueOf(2), agencies.get(0).getConsultingType());
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_returnExpectedAgenciesAndFilterOutOfflineAgencies_When_consultantAgenciesExists() {
    List<ConsultantAgency> consultantAgencies = givenConsultantAgenciesWithDeletionDateNull();

    when(consultantAgencyRepository.findByConsultantId(any())).thenReturn(consultantAgencies);
    var agencyIds =
        consultantAgencies.stream().map(ConsultantAgency::getAgencyId).collect(Collectors.toList());
    List<AgencyDTO> agencies = mockAgenciesForIds(agencyIds);
    agencies.get(0).setOffline(true);
    when(agencyService.getAgenciesNotCached(agencyIds)).thenReturn(agencies);

    var resultAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertThat(resultAgencies, hasSize(9));
    verify(agencyService, Mockito.never()).getAgencies(agencyIds);
    resultAgencies.forEach(
        agency -> {
          assertTrue(agencyIds.contains(agency.getId()));
          assertNotNull(agency.getConsultingType());
          assertNotNull(agency.getName());
          assertNotNull(agency.getCity());
          assertNotNull(agency.getDescription());
          assertNotNull(agency.getPostcode());
        });
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_returnLocalTopicAssignments_When_agencyServiceFails() {
    var activeConsultantAgency =
        new ConsultantAgency(
            AGENCY_ID, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), null, 1L, null);
    when(consultantAgencyRepository.findByConsultantId("valid"))
        .thenReturn(singletonList(activeConsultantAgency));
    when(agencyService.getAgenciesNotCached(singletonList(AGENCY_ID)))
        .thenThrow(new RuntimeException("Unauthorized"));
    when(consultantTopicRepository.findTopicIdsByConsultantId("valid")).thenReturn(List.of(1L, 2L));
    var agencyConsultingType = agencyConsultingType(AGENCY_ID, 3);
    when(sessionRepository.findLowestConsultingTypeIdsByAgencyIds(Set.of(AGENCY_ID)))
        .thenReturn(List.of(agencyConsultingType));
    ReflectionTestUtils.setField(
        consultantAgencyService, "registrationAgencyFallbackConsultingTypeId", null);

    var resultAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertThat(resultAgencies, hasSize(1));
    assertEquals(AGENCY_ID, resultAgencies.get(0).getId());
    assertEquals(List.of(1L, 2L), resultAgencies.get(0).getTopicIds());
    assertEquals(Integer.valueOf(3), resultAgencies.get(0).getConsultingType());
    verify(agencyService, Mockito.never()).getAgencyWithoutCaching(any());
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_useConfiguredFallbackConsultingType_When_agencyServiceAndSessionsFail() {
    var activeConsultantAgency =
        new ConsultantAgency(
            AGENCY_ID, CONSULTANT, AGENCY_ID, nowInUtc(), nowInUtc(), null, 1L, null);
    when(consultantAgencyRepository.findByConsultantId("valid"))
        .thenReturn(singletonList(activeConsultantAgency));
    when(agencyService.getAgenciesNotCached(singletonList(AGENCY_ID)))
        .thenThrow(new RuntimeException("Unauthorized"));
    when(consultantTopicRepository.findTopicIdsByConsultantId("valid")).thenReturn(List.of(1L));
    when(sessionRepository.findLowestConsultingTypeIdsByAgencyIds(Set.of(AGENCY_ID)))
        .thenReturn(emptyList());
    ReflectionTestUtils.setField(
        consultantAgencyService, "registrationAgencyFallbackConsultingTypeId", 1);

    var resultAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertEquals(Integer.valueOf(1), resultAgencies.get(0).getConsultingType());
    verify(agencyService, Mockito.never()).getAgencyWithoutCaching(any());
  }

  @Test
  public void
      getOnlineAgenciesOfConsultant_Should_boundFallbackCallsAndPreserveAgencyAssignments_When_batchFails() {
    var agencyIds = List.of(1L, 2L, 3L);
    var activeConsultantAgencies =
        agencyIds.stream()
            .map(
                agencyId ->
                    new ConsultantAgency(
                        agencyId, CONSULTANT, agencyId, nowInUtc(), nowInUtc(), null, 1L, null))
            .toList();
    when(consultantAgencyRepository.findByConsultantId("valid"))
        .thenReturn(activeConsultantAgencies);
    when(agencyService.getAgenciesNotCached(agencyIds))
        .thenThrow(new RuntimeException("Unavailable"));
    when(consultantTopicRepository.findTopicIdsByConsultantId("valid")).thenReturn(List.of(7L, 8L));
    var firstAgencyConsultingType = agencyConsultingType(1L, 4);
    var secondAgencyConsultingType = agencyConsultingType(2L, 5);
    when(sessionRepository.findLowestConsultingTypeIdsByAgencyIds(Set.copyOf(agencyIds)))
        .thenReturn(List.of(firstAgencyConsultingType, secondAgencyConsultingType));
    ReflectionTestUtils.setField(
        consultantAgencyService, "registrationAgencyFallbackConsultingTypeId", 9);

    var resultAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant("valid");

    assertEquals(agencyIds, resultAgencies.stream().map(AgencyDTO::getId).toList());
    assertEquals(
        List.of(4, 5, 9), resultAgencies.stream().map(AgencyDTO::getConsultingType).toList());
    assertTrue(
        resultAgencies.stream().allMatch(agency -> agency.getTopicIds().equals(List.of(7L, 8L))));
    verify(agencyService, times(1)).getAgenciesNotCached(agencyIds);
    verify(agencyService, Mockito.never()).getAgencyWithoutCaching(any());
    verify(sessionRepository, times(1))
        .findLowestConsultingTypeIdsByAgencyIds(Set.copyOf(agencyIds));
    verify(consultantTopicRepository, times(1)).findTopicIdsByConsultantId("valid");
  }

  private SessionRepository.AgencyConsultingTypeProjection agencyConsultingType(
      Long agencyId, Integer consultingTypeId) {
    var projection = Mockito.mock(SessionRepository.AgencyConsultingTypeProjection.class);
    when(projection.getAgencyId()).thenReturn(agencyId);
    when(projection.getConsultingTypeId()).thenReturn(consultingTypeId);
    return projection;
  }

  private static List<ConsultantAgency> givenConsultantAgenciesWithDeletionDateNull() {
    var consultantAgencies =
        new EasyRandom().objects(ConsultantAgency.class, 10).collect(Collectors.toList());
    consultantAgencies.stream().forEach(ca -> ca.setDeleteDate(null));
    return consultantAgencies;
  }

  private List<AgencyDTO> mockAgenciesForIds(List<Long> agencyIds) {
    return agencyIds.stream()
        .map(
            agencyId -> {
              var agencyDTO = new EasyRandom().nextObject(AgencyDTO.class);
              agencyDTO.setId(agencyId);
              return agencyDTO;
            })
        .collect(Collectors.toList());
  }
}
