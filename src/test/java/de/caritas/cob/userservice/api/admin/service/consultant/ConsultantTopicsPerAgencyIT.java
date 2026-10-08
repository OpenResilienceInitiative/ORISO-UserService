package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyTopicsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * #1264 slice C2: a counsellor's topics are stored per counselling centre (Fachbereich = centre x
 * topic). Routing by topic must keep finding the counsellor exactly as before.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ConsultantTopicsPerAgencyIT {

  // Seeded with agencies 0, 1, 257, 258, ... (UserServiceDatabase.sql).
  private static final String CONSULTANT_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";
  private static final long CENTRE_A = 1L;
  private static final long CENTRE_B = 257L;
  private static final long TOPIC_BOTH = 9101L;
  private static final long TOPIC_A_ONLY = 9102L;

  @Autowired private ConsultantUpdateService consultantUpdateService;
  @Autowired private ConsultantAdminService consultantAdminService;
  @Autowired private ConsultantDtoMapper consultantDtoMapper;
  @Autowired private ConsultantTopicRepository consultantTopicRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private ConsultantAdminFacade consultantAdminFacade;

  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;
  @MockitoBean private AccountManager accountManager;
  @MockitoBean private AppointmentService appointmentService;

  @BeforeEach
  void stubAgencyCoverage() {
    var tenantId = consultantRepository.findById(CONSULTANT_ID).orElseThrow().getTenantId();
    Map<Long, List<Long>> coverage =
        Map.of(CENTRE_A, List.of(TOPIC_BOTH, TOPIC_A_ONLY), CENTRE_B, List.of(TOPIC_BOTH));
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            invocation ->
                invocation.<List<Long>>getArgument(0).stream()
                    .map(
                        agencyId ->
                            new AgencyDTO()
                                .id(agencyId)
                                .tenantId(tenantId)
                                .topicIds(coverage.getOrDefault(agencyId, List.of())))
                    .toList());
    when(topicService.getAllTopicsMap()).thenReturn(new HashMap<>());
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void adminSetsTopicForCentreAOnly_readReturnsTopicPerCentre() {
    update(dto().topicsByAgency(List.of(centre(CENTRE_A, TOPIC_A_ONLY))));

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();

    assertThat(embedded.getTopicsByAgency()).containsExactly(centre(CENTRE_A, TOPIC_A_ONLY));
    assertThat(embedded.getTopics())
        .extracting(ConsultantTopicDTO::getId)
        .containsExactly(TOPIC_A_ONLY);
  }

  @Test
  void flatTopicIds_areStoredForEveryAssignedCentreThatOffersTheTopic() {
    update(dto().topicIds(List.of(TOPIC_BOTH, TOPIC_A_ONLY)));

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();

    assertThat(embedded.getTopicsByAgency())
        .containsExactlyInAnyOrder(
            centre(CENTRE_A, TOPIC_BOTH, TOPIC_A_ONLY), centre(CENTRE_B, TOPIC_BOTH));
    assertThat(embedded.getTopics())
        .extracting(ConsultantTopicDTO::getId)
        .containsExactlyInAnyOrder(TOPIC_BOTH, TOPIC_A_ONLY);
  }

  @Test
  void routingByTopic_stillFindsTheConsultantOnce_When_topicIsStoredForTwoCentres() {
    update(dto().topicIds(List.of(TOPIC_BOTH)));

    assertThat(consultantTopicRepository.findConsultantIdsByTopicId(TOPIC_BOTH))
        .containsExactly(CONSULTANT_ID);
    assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
        .containsExactly(TOPIC_BOTH);
  }

  @Test
  void adminList_returnsTopicsPerCentre() {
    update(dto().topicsByAgency(List.of(centre(CENTRE_B, TOPIC_BOTH))));

    var result =
        consultantDtoMapper.consultantSearchResultOf(searchResultMap(), "*", 1, 10, "", "");

    var embedded = result.getEmbedded().get(0).getEmbedded();
    assertThat(embedded.getTopicsByAgency()).containsExactly(centre(CENTRE_B, TOPIC_BOTH));
    assertThat(embedded.getTopics())
        .extracting(ConsultantTopicDTO::getId)
        .containsExactly(TOPIC_BOTH);
  }

  @Test
  void topicsByAgencyAlone_replacesExistingTopics_When_flatTopicIdsAreNotSent() {
    update(dto().topicIds(List.of(TOPIC_BOTH)));

    // The generated DTO defaults topicIds to [] although the client never sent it.
    update(dto().topicsByAgency(List.of(centre(CENTRE_A, TOPIC_A_ONLY))));

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();
    assertThat(embedded.getTopicsByAgency()).containsExactly(centre(CENTRE_A, TOPIC_A_ONLY));
  }

  @Test
  void topicsByAgency_isRejected_When_itWouldRemoveTheLastTopic() {
    update(dto().topicIds(List.of(TOPIC_BOTH)));
    var request = dto().topicsByAgency(List.of(centre(CENTRE_A)));

    assertThatThrownBy(() -> update(request)).isInstanceOf(BadRequestException.class);
    assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
        .containsExactly(TOPIC_BOTH);
  }

  @Test
  void topicsByAgency_isRejected_When_centreDoesNotOfferTheTopic() {
    var request = dto().topicsByAgency(List.of(centre(CENTRE_B, TOPIC_A_ONLY)));

    assertThatThrownBy(() -> update(request)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void topicsByAgency_isRejected_When_centreIsNotAssignedToTheConsultant() {
    var request = dto().topicsByAgency(List.of(centre(99999L, TOPIC_BOTH)));

    assertThatThrownBy(() -> update(request)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void legacyRowWithoutCentre_isReadAsEntryWithoutAgencyId() {
    jdbcTemplate.update(
        "INSERT INTO consultant_topic (id, consultant_id, topic_id, create_date, update_date)"
            + " VALUES (990001, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        CONSULTANT_ID,
        TOPIC_BOTH);

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();

    assertThat(embedded.getTopicsByAgency())
        .containsExactly(new ConsultantAgencyTopicsDTO().topicIds(List.of(TOPIC_BOTH)));
  }

  @Test
  void selfServiceProfileEdit_keepsTheTopicsTheAdminSet() {
    update(dto().topicsByAgency(List.of(centre(CENTRE_A, TOPIC_A_ONLY))));
    var consultant = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    var selfService =
        consultantDtoMapper.updateAdminConsultantOf(
            new UpdateConsultantDTO()
                .firstname("Multiple")
                .lastname("BS")
                .email("multiple@consultant.de"),
            consultant);

    consultantUpdateService.updateConsultant(CONSULTANT_ID, selfService, false);

    assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
        .containsExactly(TOPIC_A_ONLY);
  }

  @Test
  void removingACentre_deletesThatCentresTopics_andKeepsLegacyRows() {
    update(
        dto()
            .topicsByAgency(
                List.of(centre(CENTRE_A, TOPIC_BOTH, TOPIC_A_ONLY), centre(CENTRE_B, TOPIC_BOTH))));
    insertLegacyRow(TOPIC_A_ONLY);
    var remaining =
        consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID).stream()
            .map(ConsultantAgency::getAgencyId)
            .filter(agencyId -> agencyId != CENTRE_B)
            .map(agencyId -> new CreateConsultantAgencyDTO().agencyId(agencyId))
            .toList();

    consultantAdminFacade.setConsultantAgencies(CONSULTANT_ID, remaining);

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();
    assertThat(embedded.getTopicsByAgency())
        .containsExactly(
            new ConsultantAgencyTopicsDTO().topicIds(List.of(TOPIC_A_ONLY)),
            centre(CENTRE_A, TOPIC_BOTH, TOPIC_A_ONLY));
  }

  @Test
  void unscopedTopics_canBePinnedToTheCentreAFlowIsAbout() {
    insertLegacyRow(TOPIC_BOTH);

    consultantTopicRepository.assignUnscopedTopicsToAgency(CONSULTANT_ID, CENTRE_B);

    var embedded = consultantAdminService.findConsultantById(CONSULTANT_ID).getEmbedded();
    assertThat(embedded.getTopicsByAgency()).containsExactly(centre(CENTRE_B, TOPIC_BOTH));
  }

  private void insertLegacyRow(long topicId) {
    jdbcTemplate.update(
        "INSERT INTO consultant_topic (id, consultant_id, topic_id, create_date, update_date)"
            + " VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        990000 + topicId,
        CONSULTANT_ID,
        topicId);
  }

  private void update(UpdateAdminConsultantDTO request) {
    consultantUpdateService.updateConsultant(CONSULTANT_ID, request);
  }

  private static UpdateAdminConsultantDTO dto() {
    return new UpdateAdminConsultantDTO()
        .firstname("Multiple")
        .lastname("BS")
        .email("multiple@consultant.de")
        .formalLanguage(true)
        .absent(false);
  }

  private static ConsultantAgencyTopicsDTO centre(long agencyId, Long... topicIds) {
    return new ConsultantAgencyTopicsDTO().agencyId(agencyId).topicIds(List.of(topicIds));
  }

  private static Map<String, Object> searchResultMap() {
    Map<String, Object> consultant = new HashMap<>();
    consultant.put("id", CONSULTANT_ID);
    consultant.put("agencies", new ArrayList<Map<String, Object>>());
    Map<String, Object> resultMap = new HashMap<>();
    resultMap.put("consultants", List.of(consultant));
    resultMap.put("totalElements", 1);
    resultMap.put("isFirstPage", true);
    resultMap.put("isLastPage", true);
    return resultMap;
  }
}
