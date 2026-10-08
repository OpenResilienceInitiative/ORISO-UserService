package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyTopicsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import java.time.Instant;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * #1264 slice C2: a counsellor's topics are stored per counselling centre (Fachbereich = centre x
 * topic). Routing by topic must keep finding the counsellor exactly as before.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@org.springframework.context.annotation.Import(
    de.caritas.cob.userservice.api.testHelper.VerifiedRequestCallerFixture.class)
@TestPropertySource(properties = "spring.profiles.active=testing,verified-request-caller")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ConsultantTopicsPerAgencyIT {

  // Seeded with agencies 0, 1, 257, 258, ... (UserServiceDatabase.sql).
  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate boundedIdentityHttp;

  @MockitoBean private TaskIdentityGrant taskIdentityGrant;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment identityEnvironment;
  @Autowired private ObjectMapper identityMapper;
  private BoundedIdentityHttpFixtures.Provider nativeAccounts;

  private void givenBoundedAccounts() {
    nativeAccounts =
        BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskIdentityGrant,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            id -> {});
    givenHuman("7ad454de-cf29-4557-b8b3-1bf986524de2", 1L, List.of("user-admin", "tenant-admin"));
  }

  private void givenHuman(String id, Long tenant, List<String> roles) {
    var token =
        Jwt.withTokenValue("verified-creator-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "admin")
            .claim("preferred_username", "apau1")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    var authentication =
        new JwtAuthenticationToken(
            token,
            List.of(
                    "AUTHORIZATION_USER_ADMIN",
                    "AUTHORIZATION_TENANT_ADMIN",
                    "AUTHORIZATION_CONSULTANT_CREATE")
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(authentication);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @org.junit.jupiter.api.AfterEach
  void clearBoundedCaller() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

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

  @Autowired
  private de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
          .ConsultantAgencyRelationCreatorService
      importedRelations;

  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;
  @MockitoBean private AccountManager accountManager;
  @MockitoBean private AppointmentService appointmentService;

  @BeforeEach
  void stubAgencyCoverage() {
    givenBoundedAccounts();
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
    var existing = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    nativeAccounts.seed(
        new KeycloakTaskCommands.AccountProjection(
            existing.getId(),
            existing.getUsername(),
            existing.getEmail(),
            existing.getFirstName(),
            existing.getLastName(),
            existing.getTenantId(),
            "de",
            true,
            false,
            List.of("consultant"),
            false));
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

    givenHuman(CONSULTANT_ID, consultant.getTenantId(), List.of("consultant"));
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

  @Test
  void configuredCsvImport_cannotUseAnExistingCreatePermissionToAssignAnUnofferedTenantTopic()
      throws Exception {
    long otherTenantTopic = 9103L;
    var target = consultantRepository.findById(CONSULTANT_ID).orElseThrow();
    target.setTopicPermission(de.caritas.cob.userservice.api.model.TopicPermission.CREATE);
    consultantRepository.saveAndFlush(target);
    insertLegacyRow(otherTenantTopic);
    var active =
        new de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO()
            .id(otherTenantTopic)
            .name("Active tenant topic outside the selected centre");
    when(topicService.getAllActiveTopicsMap()).thenReturn(Map.of(otherTenantTopic, active));
    when(agencyService.getPublicImportAgency(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.nullable(Long.class)))
        .thenAnswer(
            invocation ->
                new AgencyDTO()
                    .id(invocation.getArgument(0))
                    .tenantId(target.getTenantId())
                    .consultingType(1)
                    .teamAgency(false)
                    .topicIds(List.of(TOPIC_BOTH, TOPIC_A_ONLY)));
    var selected =
        new AgencyDTO()
            .id(CENTRE_A)
            .tenantId(target.getTenantId())
            .consultingType(1)
            .teamAgency(false)
            .topicIds(List.of(TOPIC_BOTH, TOPIC_A_ONLY));
    var parsed = new de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord();
    parsed.setConsultantId(target.getId());
    parsed.setTenantId(target.getTenantId());
    parsed.setUsername(
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
            .decodeUsername(target.getUsername()));
    parsed.setFirstName(target.getFirstName());
    parsed.setLastName(target.getLastName());
    parsed.setEmail(target.getEmail().toLowerCase(java.util.Locale.ROOT));
    parsed.setAgenciesAndRoleSets(CENTRE_A + ";CONSULTANT_DEFAULT");
    var file = java.nio.file.Files.createTempFile("oriso-captured-existing-import-", ".csv");
    try {
      java.nio.file.Files.writeString(
          file,
          String.join(
                  ",",
                  parsed.getConsultantId(),
                  "",
                  parsed.getUsername(),
                  parsed.getFirstName(),
                  parsed.getLastName(),
                  parsed.getEmail(),
                  "",
                  "",
                  parsed.getAgenciesAndRoleSets(),
                  parsed.getTenantId() == null ? "" : parsed.getTenantId().toString())
              + "\n");
      var importer =
          Jwt.withTokenValue("verified-configured-importer")
              .header("alg", "RS256")
              .subject(
                  identityEnvironment.getRequiredProperty(
                      "identity.consultant-import.service-subject"))
              .claim(
                  "azp",
                  identityEnvironment.getRequiredProperty("identity.consultant-import.client-id"))
              .claim("realm_access", Map.of("roles", List.of("consultant-import")))
              .audience(List.of("userservice"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .build();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(
                  importer,
                  List.of(new SimpleGrantedAuthority("AUTHORIZATION_CONSULTANT_IMPORT"))));
      var configured =
          new de.caritas.cob.userservice.api.adapters.keycloak.commands.ConfiguredConsultantImport(
              file.toString(),
              identityEnvironment.getRequiredProperty("identity.consultant-import.client-id"),
              identityEnvironment.getRequiredProperty("identity.consultant-import.service-subject"),
              "userservice",
              target.getTenantId() != null);
      var captured = configured.capture();
      var row = captured.records().getFirst();
      var read =
          captured.existingRowCapability(
              row, parsed, target, List.of(selected), "account.read", List.of("consultant"));
      var additions =
          captured.existingRowCapability(
              row, parsed, target, List.of(selected), "account.roles", List.of("consultant"));
      var priorRelations =
          consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID).stream()
              .map(ConsultantAgency::getId)
              .toList();

      assertThatThrownBy(
              () ->
                  importedRelations.createExistingImportedRelations(
                      CONSULTANT_ID,
                      List.of(selected),
                      java.util.Set.of("consultant"),
                      ignored -> {},
                      read,
                      additions))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Imported agencies do not cover the consultant's existing topics");

      assertThat(consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(CONSULTANT_ID))
          .extracting(ConsultantAgency::getId)
          .containsExactlyElementsOf(priorRelations);
      assertThat(consultantTopicRepository.findTopicIdsByConsultantId(CONSULTANT_ID))
          .contains(otherTenantTopic);
      assertThat(nativeAccounts.commands())
          .extracting(BoundedIdentityHttpFixtures.Command::operation)
          .doesNotContain("account.read", "account.roles", "account.create");
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
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
