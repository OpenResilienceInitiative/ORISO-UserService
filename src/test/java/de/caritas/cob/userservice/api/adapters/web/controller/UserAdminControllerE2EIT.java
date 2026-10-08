package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.ADMIN_DATA_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.AGENCY_ADMIN_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.CONSULTANT_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.TENANT_ADMIN_PATH;
import static de.caritas.cob.userservice.api.adapters.web.controller.UserAdminControllerIT.TENANT_ADMIN_PATH_WITHOUT_SLASH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.ConsultingTypeServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.TopicServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.ConsultantTopic;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
import de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup;
import de.caritas.cob.userservice.consultingtypeservice.generated.ApiClient;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.ConsultingTypeControllerApi;
import de.caritas.cob.userservice.mailservice.generated.web.MailsControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.topicservice.generated.web.TopicControllerApi;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minidev.json.JSONArray;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"feature.topics.enabled=true", "multitenancy.enabled=false"})
@Transactional
class UserAdminControllerE2EIT extends AccountInactivityPolicyHttpFixture {
  @org.junit.jupiter.api.BeforeEach
  void recoveryPolicyFixture() {
    org.mockito.Mockito.when(
            tenantService.getRestrictedTenantDataFresh(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
  }

  private static final EasyRandom easyRandom = new EasyRandom();

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  public static final int PAGE_SIZE = 10;
  @Autowired private MockMvc mockMvc;

  @Autowired
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      lifecycle;

  private final java.util.Map<String, Instant> fixtureLifecycleRows =
      new java.util.LinkedHashMap<>();
  @MockitoBean private TaskIdentityGrant taskGrants;
  @MockitoBean private org.springframework.security.oauth2.jwt.JwtDecoder taskJwtDecoder;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment environment;
  private BoundedIdentityHttpFixtures.Provider identityProvider;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ConsultingTypeControllerApi consultingTypeControllerApi;

  @Autowired private IdentityConfig identityConfig;

  @Autowired private AdminRepository adminRepository;

  @Autowired private AccountInviteRepository accountInvites;

  @Autowired private PlatformTransactionManager transactions;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private EntityManager entityManager;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @MockitoBean
  private ConsultingTypeServiceApiControllerFactory consultingTypeServiceApiControllerFactory;

  @MockitoBean private MailServiceApiControllerFactory mailServiceApiControllerFactory;

  @MockitoBean
  @Qualifier("restTemplate")
  private RestTemplate restTemplate;

  @MockitoBean
  @Qualifier("keycloakRestTemplate")
  private RestTemplate keycloakRestTemplate;

  @MockitoBean
  @Qualifier("topicControllerApiPrimary")
  private TopicControllerApi topicControllerApi;

  @MockitoBean private TopicServiceApiControllerFactory topicServiceApiControllerFactory;

  @MockitoBean
  @Qualifier("mailsControllerApi")
  private MailsControllerApi mailsControllerApi;

  @MockitoBean AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean TenantService tenantService;

  @MockitoBean private EmailBrandingResolver branding;

  @MockitoBean private TenantEmailBrandValues brandValues;

  @MockitoBean private OrisoEmailRenderer renderer;

  @MockitoBean private InviteMailDispatchService setupMail;

  private String createdIdentityId;
  private String cleanupIdentityId;
  private boolean cleanupConsultant;

  private User user;

  @AfterEach
  void reset() {
    fixtureLifecycleRows.forEach(
        (id, capturedAt) -> lifecycle.discardUncompletedCreation(id, 24, 0, capturedAt));
    fixtureLifecycleRows.clear();
    try {
      if (cleanupConsultant) {
        ExistingAccountSetupFixtureCleanup.consultant(
            jdbcTemplate, transactions, cleanupIdentityId);
      } else {
        ExistingAccountSetupFixtureCleanup.admin(jdbcTemplate, transactions, cleanupIdentityId);
      }
    } finally {
      if (cleanupIdentityId != null) {
        new TransactionTemplate(transactions)
            .executeWithoutResult(
                status -> {
                  jdbcTemplate.update(
                      "DELETE FROM identity_creation_attempt WHERE account_id = ?",
                      cleanupIdentityId);
                  jdbcTemplate.update(
                      "DELETE FROM account_inactivity WHERE identity_id = ?", cleanupIdentityId);
                });
      }
      identityConfig.setDisplayNameAllowedForConsultants(false);
    }
  }

  @BeforeEach
  public void setUp() {
    when(consultingTypeControllerApi.getApiClient()).thenReturn(new ApiClient(restTemplate));
    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TestAgencyControllerApi(
                new de.caritas.cob.userservice.agencyserivce.generated.ApiClient()));

    when(consultingTypeServiceApiControllerFactory.createControllerApi())
        .thenReturn(consultingTypeControllerApi);
    when(mailServiceApiControllerFactory.createControllerApi()).thenReturn(mailsControllerApi);
    when(topicServiceApiControllerFactory.createControllerApi()).thenReturn(topicControllerApi);
    when(topicControllerApi.getApiClient())
        .thenReturn(new de.caritas.cob.userservice.topicservice.generated.ApiClient());

    createdIdentityId = null;
    cleanupIdentityId = null;
    identityProvider =
        BoundedIdentityHttpFixtures.givenProvider(
            keycloakRestTemplate,
            taskGrants,
            taskIdentities,
            environment,
            objectMapper,
            id -> {
              createdIdentityId = id;
              cleanupIdentityId = id;
            });
    BoundedIdentityHttpFixtures.givenTaskGrants(restTemplate, taskJwtDecoder, taskIdentities);
    givenVerifiedHuman(
        "1c80e100-266f-4a02-a3a3-703f236f4a63", 0L, List.of("tenant-admin", "agency-admin"));
    var resolved = new EmailBranding("Test product", null, "#124078", null, null);
    when(branding.resolve(Mockito.nullable(Long.class))).thenReturn(resolved);
    when(brandValues.values(Mockito.eq(resolved), Mockito.nullable(Long.class)))
        .thenReturn(Map.of("platformName", "Test product"));
    when(renderer.render(Mockito.eq("konto-einrichten"), Mockito.any(), Mockito.any()))
        .thenReturn(new OrisoEmailRenderer.RenderedEmail("Setup", "<p>Setup</p>", "Setup"));
    when(setupMail.sendRendered(Mockito.anyString(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> new InviteMailSendReceipt(invocation.getArgument(0), Instant.now()));
  }

  private void givenCurrentSetupIdentity(
      String username, String email, AccountInviteTargetRole role, boolean consultant) {
    cleanupConsultant = consultant;
    // The actual bounded creation command supplies the identity and setup-required projection.

  }

  private void assertIssuedSetup(AccountInviteTargetRole role, Long tenantId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var invite =
                  accountInvites.findByActiveSetupIdentityKey(createdIdentityId).orElseThrow();
              assertThat(invite.getPurpose())
                  .isEqualTo(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP);
              assertThat(invite.getProvisionedUserId()).isEqualTo(createdIdentityId);
              assertThat(invite.getTargetRole()).isEqualTo(role);
              assertThat(invite.getTenantId()).isEqualTo(tenantId);
            });
    assertThat(identityProvider.commands())
        .anySatisfy(
            command -> {
              assertThat(command.operation()).isEqualTo("account.read");
              assertThat(command.target()).isEqualTo(createdIdentityId);
            });
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_CREATE})
  void createNewConsultant_Should_returnOk_When_requiredConsultantIsGiven() throws Exception {
    givenNewConsultantIsCreated();
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CREATE_NEW_CHAT})
  void createNewConsultant_WithoutValidCredentials_Should_returnAccessDenied() throws Exception {
    // given
    CreateConsultantDTO createAdminDTO = new EasyRandom().nextObject(CreateConsultantDTO.class);
    createAdminDTO.setEmail("consultant@email.com");

    // when, then
    this.mockMvc
        .perform(
            post(CONSULTANT_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_CREATE})
  void createNewConsultant_WithAuthorityConsultantCreateUpdate_Should_returnOK() throws Exception {
    givenNewConsultantIsCreated();
  }

  private String givenNewConsultantIsCreated() throws Exception {
    // given
    CreateConsultantDTO createAdminDTO = new EasyRandom().nextObject(CreateConsultantDTO.class);
    createAdminDTO.setTenantId(1L);
    createAdminDTO.setEmail("consultant@email.com");
    givenCurrentSetupIdentity(
        createAdminDTO.getUsername(),
        createAdminDTO.getEmail(),
        AccountInviteTargetRole.COUNSELLOR,
        true);
    // when
    MvcResult mvcResult =
        this.mockMvc
            .perform(
                post(CONSULTANT_PATH)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createAdminDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded.id", notNullValue()))
            .andExpect(jsonPath("_embedded.username", notNullValue()))
            .andExpect(jsonPath("_embedded.lastname", notNullValue()))
            .andExpect(jsonPath("_embedded.email", is("consultant@email.com")))
            .andReturn();
    String content = mvcResult.getResponse().getContentAsString();
    assertIssuedSetup(AccountInviteTargetRole.COUNSELLOR, 1L);
    return JsonPath.read(content, "_embedded.id");
  }

  private static final String CONSULTANT_WITH_LANGUAGES_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_UPDATE})
  void updateConsultant_Should_keepLanguages_When_adminBodyOmitsLanguages() throws Exception {
    var consultant = consultantRepository.findById(CONSULTANT_WITH_LANGUAGES_ID).orElseThrow();
    consultant.setLanguages(
        Set.of(
            new Language(consultant, LanguageCode.de), new Language(consultant, LanguageCode.en)));
    consultantRepository.save(consultant);
    entityManager.flush();

    // The exact key set ORISO-Admin's editCounselorData.ts sends for an untouched edit form:
    // it has no languages field at all.
    var body = new LinkedHashMap<String, Object>();
    body.put("firstname", consultant.getFirstName());
    body.put("lastname", consultant.getLastName());
    body.put("formalLanguage", consultant.isLanguageFormal());
    body.put("email", consultant.getEmail());
    body.put("absent", consultant.isAbsent());
    body.put("isSupervisor", consultant.isSupervisor());
    body.put("topicIds", List.of());
    body.put("rejectPendingPublicSlug", false);

    this.mockMvc
        .perform(
            put(CONSULTANT_PATH + "/" + CONSULTANT_WITH_LANGUAGES_ID)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());

    entityManager.flush();
    entityManager.clear();
    assertThat(languageCodesOf(CONSULTANT_WITH_LANGUAGES_ID)).containsExactlyInAnyOrder("de", "en");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_UPDATE})
  void updateConsultant_Should_keepTopics_When_adminBodyOmitsTopicIds() throws Exception {
    givenConsultantWithTopics(CONSULTANT_WITH_LANGUAGES_ID, 1L, 2L);
    var body = adminEditBodyFor(CONSULTANT_WITH_LANGUAGES_ID);

    putConsultant(CONSULTANT_WITH_LANGUAGES_ID, body).andExpect(status().isOk());

    entityManager.flush();
    entityManager.clear();
    assertThat(topicIdsOf(CONSULTANT_WITH_LANGUAGES_ID)).containsExactlyInAnyOrder(1L, 2L);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_UPDATE})
  void updateConsultant_Should_rejectRemovingEveryTopic_When_adminBodySendsEmptyTopicIds()
      throws Exception {
    givenConsultantWithTopics(CONSULTANT_WITH_LANGUAGES_ID, 1L, 2L);
    var body = adminEditBodyFor(CONSULTANT_WITH_LANGUAGES_ID);
    body.put("topicIds", List.of());

    putConsultant(CONSULTANT_WITH_LANGUAGES_ID, body).andExpect(status().isBadRequest());

    // Without the flush, a removal the service made before refusing would never reach the table.
    entityManager.flush();
    entityManager.clear();
    assertThat(topicIdsOf(CONSULTANT_WITH_LANGUAGES_ID)).containsExactlyInAnyOrder(1L, 2L);
  }

  private void givenConsultantWithTopics(String consultantId, Long... topicIds) {
    var consultant = consultantRepository.findById(consultantId).orElseThrow();
    consultant.replaceTopics(List.of(topicIds));
    consultantRepository.save(consultant);
    entityManager.flush();
  }

  private LinkedHashMap<String, Object> adminEditBodyFor(String consultantId) {
    var consultant = consultantRepository.findById(consultantId).orElseThrow();
    var body = new LinkedHashMap<String, Object>();
    body.put("firstname", consultant.getFirstName());
    body.put("lastname", consultant.getLastName());
    body.put("formalLanguage", consultant.isLanguageFormal());
    body.put("email", consultant.getEmail());
    body.put("absent", consultant.isAbsent());
    body.put("rejectPendingPublicSlug", false);
    return body;
  }

  private org.springframework.test.web.servlet.ResultActions putConsultant(
      String consultantId, Object body) throws Exception {
    return this.mockMvc.perform(
        put(CONSULTANT_PATH + "/" + consultantId)
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private Set<Long> topicIdsOf(String consultantId) {
    return consultantRepository.findById(consultantId).orElseThrow().getConsultantTopics().stream()
        .map(ConsultantTopic::getTopicId)
        .collect(Collectors.toSet());
  }

  private Set<String> languageCodesOf(String consultantId) {
    return consultantRepository.findById(consultantId).orElseThrow().getLanguages().stream()
        .map(language -> language.getLanguageCode().name())
        .collect(Collectors.toSet());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void createNewAgencyAdmin_Should_returnOk_When_requiredCreateAgencyAdminIsGiven()
      throws Exception {
    givenNewAgencyAdminIsCreated();
  }

  private String givenNewAgencyAdminIsCreated() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");
    createAdminDTO.setTenantId(95);
    givenCurrentSetupIdentity(
        createAdminDTO.getUsername(),
        createAdminDTO.getEmail(),
        AccountInviteTargetRole.AGENCY_ADMIN,
        false);

    // when

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                post(AGENCY_ADMIN_PATH)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createAdminDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded.id", notNullValue()))
            .andExpect(jsonPath("_embedded.username", notNullValue()))
            .andExpect(jsonPath("_embedded.lastname", notNullValue()))
            .andExpect(jsonPath("_embedded.email", is("agencyadmin@email.com")))
            .andExpect(jsonPath("_embedded.tenantId", is("null")))
            .andReturn();
    String content = mvcResult.getResponse().getContentAsString();
    assertIssuedSetup(AccountInviteTargetRole.AGENCY_ADMIN, null);
    return JsonPath.read(content, "_embedded.id");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void createNewAgencyAdmin_Should_returnForbidden_When_calledNotAsUserAdmin() throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("agencyadmin@email.com");

    // when

    this.mockMvc
        .perform(
            post(AGENCY_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void createNewTenantAdmin_Should_returnOk_When_requiredCreateTenantAdminIsGiven()
      throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("valid@email.com");
    createAdminDTO.setTenantId(1);
    givenCurrentSetupIdentity(
        createAdminDTO.getUsername(),
        createAdminDTO.getEmail(),
        AccountInviteTargetRole.TENANT_ADMIN,
        false);

    // when

    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", notNullValue()))
        .andExpect(jsonPath("_embedded.username", notNullValue()))
        .andExpect(jsonPath("_embedded.lastname", notNullValue()))
        .andExpect(jsonPath("_embedded.email", is("valid@email.com")));
    assertIssuedSetup(AccountInviteTargetRole.TENANT_ADMIN, 1L);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void
      createNewTenantAdmin_Should_returnBadRequest_When_requiredCreateTenantAdminIsGivenButTenantIdIsNull()
          throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("valid@email.com");
    createAdminDTO.setTenantId(null);

    // when

    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void
      createNewTenantAdmin_Should_returnForbidden_When_attemptedToCreateTenantAdminWithoutTenantAdminAuthority()
          throws Exception {
    // given
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("valid@email.com");

    // when
    this.mockMvc
        .perform(
            post(TENANT_ADMIN_PATH)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void updateAgencyAdmin_Should_returnOk_When_updateAttemptAsUserAdmin() throws Exception {
    // given
    // #968: this class runs with multitenancy.enabled=false, so CreateAdminService stores the
    // new admin with a null tenant. The scope check rejects a null on either side, so only a
    // platform admin can reach the endpoint here; the same-tenant path is covered by
    // AgencyAdminUserServiceTest against a target that actually has a tenant.
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    String adminId = givenNewAgencyAdminIsCreated();

    UpdateAgencyAdminDTO updateAdminDTO = new EasyRandom().nextObject(UpdateAgencyAdminDTO.class);

    updateAdminDTO.setFirstname("changedFirstname");
    updateAdminDTO.setLastname("changedLastname");
    updateAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            put(AGENCY_ADMIN_PATH + "/" + adminId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(adminId)))
        .andExpect(jsonPath("_embedded.firstname", is("changedFirstname")))
        .andExpect(jsonPath("_embedded.lastname", is("changedLastname")))
        .andExpect(jsonPath("_embedded.email", is("changed@email.com")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.RESTRICTED_AGENCY_ADMIN})
  void patchAdminData_Should_returnOk_When_patchAttemptAsRestrictedAgencyAdmin() throws Exception {
    // given
    String adminId = "6d15b3ff-2394-4d9f-9ea5-e958afe6a65c";
    when(authenticatedUser.getUserId()).thenReturn(adminId);
    var ownedAdmin = adminRepository.findById(adminId).orElseThrow();
    givenVerifiedHuman(
        adminId,
        ownedAdmin.getTenantId(),
        List.of(
            ownedAdmin.getType() == AdminType.TENANT ? "tenant-admin" : "restricted-agency-admin"));
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(true);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);

    PatchAdminDTO patchAdminDTO = new EasyRandom().nextObject(PatchAdminDTO.class);

    patchAdminDTO.setFirstname("changedFirstname");
    patchAdminDTO.setLastname("changedLastname");
    patchAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            patch(ADMIN_DATA_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(adminId)))
        .andExpect(jsonPath("_embedded.firstname", is("changedFirstname")))
        .andExpect(jsonPath("_embedded.lastname", is("changedLastname")))
        .andExpect(jsonPath("_embedded.email", is("changed@email.com")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void patchAdminData_Should_returnOk_When_patchAttemptAsSingleTenantAdmin() throws Exception {
    // given
    String adminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";
    when(authenticatedUser.getUserId()).thenReturn(adminId);
    var ownedAdmin = adminRepository.findById(adminId).orElseThrow();
    givenVerifiedHuman(
        adminId,
        ownedAdmin.getTenantId(),
        List.of(
            ownedAdmin.getType() == AdminType.TENANT ? "tenant-admin" : "restricted-agency-admin"));
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(true);
    // #968: self-patch dispatches to patchTenantAdmin, which scopes to the caller's tenant.
    // The caller here *is* the admin being patched, so it carries that admin's own tenant
    // (102 for cgenney5 in UserServiceDatabase.sql). Reporting platform admin instead would
    // short-circuit the check and leave the same-tenant path this test exists for unproven.
    when(authenticatedUser.getTenantId()).thenReturn(102L);

    PatchAdminDTO patchAdminDTO = new EasyRandom().nextObject(PatchAdminDTO.class);

    patchAdminDTO.setFirstname("changedFirstname");
    patchAdminDTO.setLastname("changedLastname");
    patchAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            patch(ADMIN_DATA_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(adminId)))
        .andExpect(jsonPath("_embedded.firstname", is("changedFirstname")))
        .andExpect(jsonPath("_embedded.lastname", is("changedLastname")))
        .andExpect(jsonPath("_embedded.email", is("changed@email.com")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void patchAdminData_Should_returnOk_When_patchAttemptAsPlatformAdmin() throws Exception {
    // given
    // #968: a platform admin keeps the cross-tenant view, so the scope check short-circuits
    // and no caller tenant is needed. Kept separate from the single-tenant case so that one
    // still exercises the same-tenant path.
    String adminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";
    when(authenticatedUser.getUserId()).thenReturn(adminId);
    var ownedAdmin = adminRepository.findById(adminId).orElseThrow();
    givenVerifiedHuman(
        adminId,
        ownedAdmin.getTenantId(),
        List.of(
            ownedAdmin.getType() == AdminType.TENANT ? "tenant-admin" : "restricted-agency-admin"));
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(true);
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    PatchAdminDTO patchAdminDTO = new EasyRandom().nextObject(PatchAdminDTO.class);

    patchAdminDTO.setFirstname("changedFirstname");
    patchAdminDTO.setLastname("changedLastname");
    patchAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            patch(ADMIN_DATA_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(adminId)))
        .andExpect(jsonPath("_embedded.firstname", is("changedFirstname")))
        .andExpect(jsonPath("_embedded.lastname", is("changedLastname")))
        .andExpect(jsonPath("_embedded.email", is("changed@email.com")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
  void patchAdminData_Should_returnForbidden_When_patchAttemptAsNonAdminUser() throws Exception {
    patchAdminAndExpectForbidden();
  }

  private void patchAdminAndExpectForbidden() throws Exception {
    // given
    String adminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";
    when(authenticatedUser.getUserId()).thenReturn(adminId);
    var ownedAdmin = adminRepository.findById(adminId).orElseThrow();
    givenVerifiedHuman(
        adminId,
        ownedAdmin.getTenantId(),
        List.of(
            ownedAdmin.getType() == AdminType.TENANT ? "tenant-admin" : "restricted-agency-admin"));
    when(authenticatedUser.isRestrictedAgencyAdmin()).thenReturn(false);
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(false);

    PatchAdminDTO patchAdminDTO = new EasyRandom().nextObject(PatchAdminDTO.class);

    patchAdminDTO.setFirstname("changedFirstname");
    patchAdminDTO.setLastname("changedLastname");
    patchAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            patch(ADMIN_DATA_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void
      patchAdminData_Should_returnForbidden_When_patchAttemptAsUserAdminButNotSingleTenantAdminOrRestrictedAgencyAdmin()
          throws Exception {
    patchAdminAndExpectForbidden();
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void updateAgencyAdmin_Should_returnForbidden_When_UpdateAttemptAsNonUserAdmin()
      throws Exception {
    // given
    String adminId = "5606179b-77e7-4056-aedc-68ddc769890c";

    UpdateAgencyAdminDTO updateAdminDTO = new UpdateAgencyAdminDTO();
    updateAdminDTO.setFirstname("changedFirstname");
    updateAdminDTO.setLastname("changedLastname");
    updateAdminDTO.setEmail("changed@email.com");

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            put(AGENCY_ADMIN_PATH + "/" + adminId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void updateTenantAdmin_Should_returnOk_When_updateAttemptAsTenantAdmin() throws Exception {
    // given
    // #968: multitenancy.enabled=false here, so the freshly created admin has a null tenant
    // and the scope check can never match a caller tenant. Platform admin is the only caller
    // that can reach this endpoint in this setup; TenantAdminUserServiceTest covers the
    // same-tenant path against a target that has a tenant.
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    String adminId = givenNewTenantAdminIsCreated();

    UpdateTenantAdminDTO updateAdminDTO = new EasyRandom().nextObject(UpdateTenantAdminDTO.class);

    updateAdminDTO.setFirstname("changedFirstname");
    updateAdminDTO.setLastname("changedLastname");
    updateAdminDTO.setEmail("changed@email.com");
    updateAdminDTO.setTenantId(1);

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain"));

    // when, then
    this.mockMvc
        .perform(
            put(TENANT_ADMIN_PATH + "/" + adminId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateAdminDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(adminId)))
        .andExpect(jsonPath("_embedded.firstname", is("changedFirstname")))
        .andExpect(jsonPath("_embedded.lastname", is("changedLastname")))
        .andExpect(jsonPath("_embedded.email", is("changed@email.com")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void
      updateTenantAdmin_Should_returnForbidden_When_attemptedToUpdatedTenantAdminWithoutTenantAdminAuthority()
          throws Exception {
    // given
    UpdateTenantAdminDTO updateAdminDTO = new EasyRandom().nextObject(UpdateTenantAdminDTO.class);
    var existingAdminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";
    updateAdminDTO.setFirstname("changedFirstname");
    updateAdminDTO.setLastname("changedLastname");
    updateAdminDTO.setEmail("changed@email.com");
    updateAdminDTO.setTenantId(1);

    // when, then
    this.mockMvc
        .perform(
            put(TENANT_ADMIN_PATH + "/" + existingAdminId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateAdminDTO)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void getAgencydmin_Should_returnOk_When_attemptedToGetAgencyAdminWithTenantAdminAuthority()
      throws Exception {
    // given
    var existingAdminId = "5606179b-77e7-4056-aedc-68ddc769890c";

    // when, then
    this.mockMvc
        .perform(get(AGENCY_ADMIN_PATH + "/" + existingAdminId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(existingAdminId)))
        .andExpect(jsonPath("_embedded.username", is("bmachin1j")))
        .andExpect(jsonPath("_embedded.firstname", is("Barn")))
        .andExpect(jsonPath("_embedded.lastname", is("Machin")))
        .andExpect(jsonPath("_embedded.email", is("bmachin1j@senate.gov")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.SINGLE_TENANT_ADMIN})
  void
      getAgencydmin_Should_returnForbidden_When_attemptedToGetAgencyAdminWithoutUserAdminAuthority()
          throws Exception {
    // given
    var existingAdminId = "5606179b-77e7-4056-aedc-68ddc769890c";

    // when, then
    this.mockMvc
        .perform(get(AGENCY_ADMIN_PATH + "/" + existingAdminId))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void getTenantAdmin_Should_returnOk_When_attemptedToGetTenantAdminWithTenantAdminAuthority()
      throws Exception {
    // given
    // #968: reading a tenant admin is scoped to the caller's tenant. cgenney5 sits in tenant
    // 102 (UserServiceDatabase.sql), so the caller carries that tenant and the same-tenant
    // branch is the one under test.
    when(authenticatedUser.getTenantId()).thenReturn(102L);
    var existingAdminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";

    // when, then
    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH + "/" + existingAdminId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("_embedded.id", is(existingAdminId)))
        .andExpect(jsonPath("_embedded.username", is("cgenney5")))
        .andExpect(jsonPath("_embedded.firstname", is("Ceil")))
        .andExpect(jsonPath("_embedded.lastname", is("Genney")))
        .andExpect(jsonPath("_embedded.email", is("cgenney5@imageshack.us")));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void
      getTenantAdmins_Should_returnOkAndFilterByTenantId_When_attemptedToGetTenantWithTenantAdminAuthority()
          throws Exception {
    // #968: cross-tenant listing is now platform-admin only.
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    // when, then
    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH_WITHOUT_SLASH + "?tenantId=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size()", is(1)))
        .andExpect(jsonPath("$.[0]._embedded.tenantId", is("2")))
        .andReturn();

    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH_WITHOUT_SLASH + "?tenantId=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size()", is(0)))
        .andReturn();
  }

  @Test
  @WithMockUser(
      authorities = {
        AuthorityValue.SINGLE_TENANT_ADMIN,
        AuthorityValue.USER_ADMIN,
        AuthorityValue.RESTRICTED_AGENCY_ADMIN
      })
  void
      getTenantAdmins_Should_returnForbidden_When_attemptedToGetTenantAdminsWithNonSuperTenantAdminAuthority()
          throws Exception {

    // when, then
    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH_WITHOUT_SLASH + "?tenantId=2"))
        .andExpect(status().isForbidden());

    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH_WITHOUT_SLASH + "?tenantId=1"))
        .andExpect(status().isForbidden());

    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH_WITHOUT_SLASH + "?tenantId=0"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmin_Should_returnOk_When_attemptedToSearchTenantsWithTenantAdminAuthority()
      throws Exception {

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));
    // when, then
    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/tenantadmins/search?query=*&page=1&perPage=10&order=ASC&field=FIRSTNAME"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(PAGE_SIZE)))
            .andExpect(jsonPath("_embedded[0]._embedded.id").exists())
            .andExpect(jsonPath("_embedded[0]._embedded.username").exists())
            .andExpect(jsonPath("_embedded[0]._embedded.firstname").exists())
            .andExpect(jsonPath("_embedded[0]._embedded.lastname").exists())
            .andExpect(jsonPath("_embedded[0]._embedded.email").exists())
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, AdminType.TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdminsKeepsProtectedPlatformMetadataWithoutReadingItsNativeStatus()
      throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    when(authenticatedUser.getTenantId()).thenReturn(0L);
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));
    String protectedId = "7b1e15fe-4039-4ce7-a078-05996ff06676";
    String ordinaryId = "382517bc-7b9d-4c44-8d33-6b8638201c98";
    for (String id : List.of(protectedId, ordinaryId)) {
      var admin = adminRepository.findById(id).orElseThrow();
      identityProvider.seed(
          new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
              .AccountProjection(
              id,
              admin.getUsername(),
              admin.getEmail(),
              admin.getFirstName(),
              admin.getLastName(),
              admin.getTenantId(),
              "de",
              true,
              false,
              List.of("tenant-admin"),
              false));
    }

    var response =
        mockMvc
            .perform(
                get(
                    "/useradmin/tenantadmins/search?query=*&page=1&perPage=100&order=ASC&field=FIRSTNAME"))
            .andExpect(status().isOk())
            .andReturn();
    var rows = objectMapper.readTree(response.getResponse().getContentAsString()).path("_embedded");
    com.fasterxml.jackson.databind.JsonNode protectedRow = null;
    com.fasterxml.jackson.databind.JsonNode ordinaryRow = null;
    for (var row : rows) {
      var account = row.path("_embedded");
      if (protectedId.equals(account.path("id").asText())) protectedRow = account;
      if (ordinaryId.equals(account.path("id").asText())) ordinaryRow = account;
    }
    assertThat(protectedRow).isNotNull();
    assertThat(protectedRow.path("username").asText()).isEqualTo("nbaile8");
    assertThat(protectedRow.path("active").isNull() || protectedRow.path("active").isMissingNode())
        .isTrue();
    assertThat(ordinaryRow).isNotNull();
    assertThat(ordinaryRow.path("active").isBoolean()).isTrue();
    assertThat(ordinaryRow.path("active").asBoolean()).isTrue();
    assertThat(identityProvider.commands())
        .noneMatch(
            command ->
                command.operation().equals("account.read") && command.target().equals(protectedId));
    assertThat(identityProvider.commands())
        .anyMatch(
            command ->
                command.operation().equals("account.read") && command.target().equals(ordinaryId));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmin_Should_acceptPageSizesAboveTenantBatchLimit() throws Exception {
    this.mockMvc
        .perform(
            get(
                "/useradmin/tenantadmins/search?query=*&page=1&perPage=101&order=ASC&field=FIRSTNAME"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmin_Should_acceptPageSizeAtTenantBatchLimit() throws Exception {
    this.mockMvc
        .perform(
            get(
                "/useradmin/tenantadmins/search?query=*&page=1&perPage=100&order=ASC&field=FIRSTNAME"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmin_Should_returnOk_When_sortingByUpdateDate() throws Exception {

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/tenantadmins/search?query=*&page=1&perPage=10&order=DESC&field=UPDATE_DATE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(PAGE_SIZE)))
            .andExpect(jsonPath("_embedded[0]._embedded.updateDate").exists())
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, AdminType.TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchTenantAdmin_Should_returnOk_When_attemptedToSearchTenantsWithUserAdminAuthority()
      throws Exception {

    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/tenantadmins/search?query=*&page=1&perPage=10&order=ASC&field=FIRSTNAME"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(PAGE_SIZE)))
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, AdminType.TENANT);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_returnOk_When_attemptedToSearchTenantsWithUserAdminAuthority()
      throws Exception {
    // when
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));
    // then
    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/agencyadmins/search?query=*&page=1&perPage=10&order=ASC&field=FIRSTNAME"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(PAGE_SIZE)))
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, AdminType.AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_acceptPageSizesAboveTenantBatchLimit() throws Exception {
    this.mockMvc
        .perform(
            get(
                "/useradmin/agencyadmins/search?query=*&page=1&perPage=101&order=ASC&field=FIRSTNAME"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_acceptPageSizeAtTenantBatchLimit() throws Exception {
    this.mockMvc
        .perform(
            get(
                "/useradmin/agencyadmins/search?query=*&page=1&perPage=100&order=ASC&field=FIRSTNAME"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_returnOk_When_sortingByUpdateDate() throws Exception {
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));

    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/agencyadmins/search?query=*&page=1&perPage=10&order=DESC&field=UPDATE_DATE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(PAGE_SIZE)))
            .andExpect(jsonPath("_embedded[0]._embedded.updateDate").exists())
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, AdminType.AGENCY);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmins_Should_sortByUpdateDateFallingBackToCreateDate_When_fieldIsUpdateDate()
      throws Exception {
    when(authenticatedUser.getTenantId()).thenReturn(LAST_UPDATED_TENANT_ID);
    givenAdminsWithLastUpdatedDates(AdminType.TENANT);

    assertSearchOrder("/useradmin/tenantadmins/search", "DESC", LAST_UPDATED_DESC_ORDER);
    assertSearchOrder("/useradmin/tenantadmins/search", "ASC", LAST_UPDATED_ASC_ORDER);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_sortByUpdateDateFallingBackToCreateDate_When_fieldIsUpdateDate()
      throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    givenAdminsWithLastUpdatedDates(AdminType.AGENCY);

    assertSearchOrder("/useradmin/agencyadmins/search", "DESC", LAST_UPDATED_DESC_ORDER);
    assertSearchOrder("/useradmin/agencyadmins/search", "ASC", LAST_UPDATED_ASC_ORDER);
  }

  private static final Long LAST_UPDATED_TENANT_ID = 4242L;
  private static final String LAST_UPDATED_PROBE = "lastupdatedprobe";
  // "Zuletzt aktualisiert" = update_date, else create_date; ties broken by id.
  private static final List<String> LAST_UPDATED_DESC_ORDER =
      List.of("b1-sort-admin-4", "b1-sort-admin-2", "b1-sort-admin-3", "b1-sort-admin-1");
  private static final List<String> LAST_UPDATED_ASC_ORDER =
      List.of("b1-sort-admin-1", "b1-sort-admin-3", "b1-sort-admin-2", "b1-sort-admin-4");

  private void givenAdminsWithLastUpdatedDates(AdminType type) {
    givenAdminWithDates("b1-sort-admin-1", type, at(2020, 1), at(2020, 6));
    givenAdminWithDates("b1-sort-admin-2", type, at(2025, 1), null);
    givenAdminWithDates("b1-sort-admin-3", type, at(2019, 1), at(2022, 1));
    givenAdminWithDates("b1-sort-admin-4", type, at(2025, 1), null);
  }

  private static LocalDateTime at(int year, int month) {
    return LocalDateTime.of(year, month, 1, 12, 0);
  }

  private void givenAdminWithDates(
      String id, AdminType type, LocalDateTime createDate, LocalDateTime updateDate) {
    adminRepository.saveAndFlush(
        Admin.builder()
            .id(id)
            .type(type)
            .tenantId(LAST_UPDATED_TENANT_ID)
            .username(id)
            .firstName("First")
            .lastName("Last")
            .email(LAST_UPDATED_PROBE + "-" + id + "@example.com")
            .build());
    // Auditing stamps both dates on save; overwrite them, including a missing update date.
    jdbcTemplate.update(
        "UPDATE admin SET create_date = ?, update_date = ? WHERE admin_id = ?",
        createDate,
        updateDate,
        id);
  }

  private void assertSearchOrder(String path, String order, List<String> expectedIds)
      throws Exception {
    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    path
                        + "?query="
                        + LAST_UPDATED_PROBE
                        + "&page=1&perPage=10&field=UPDATE_DATE&order="
                        + order))
            .andExpect(status().isOk())
            .andReturn();

    List<String> ids =
        JsonPath.read(mvcResult.getResponse().getContentAsString(), "$._embedded[*]._embedded.id");
    assertThat(ids).as("order %s", order).containsExactlyElementsOf(expectedIds);
  }

  // #1263 B3: Träger (tenantId) and Beratungsstelle (agencyId) filters on the admin searches.
  private static final String FILTER_PROBE = "b3filterprobe";
  private static final Long FILTER_TENANT_A = 5151L;
  private static final Long FILTER_TENANT_B = 5252L;
  private static final Long FILTER_AGENCY_X = 61001L;
  private static final Long FILTER_AGENCY_Y = 61002L;
  private static final String TENANT_ADMINS_SEARCH = "/useradmin/tenantadmins/search";
  private static final String AGENCY_ADMINS_SEARCH = "/useradmin/agencyadmins/search";

  @Autowired private AdminAgencyRepository adminAgencyRepository;

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmins_Should_returnOnlyThatTenant_When_platformAdminFiltersByTenantId()
      throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    givenFilterAdmin("b3-tenant-a1", AdminType.TENANT, FILTER_TENANT_A);
    givenFilterAdmin("b3-tenant-a2", AdminType.TENANT, FILTER_TENANT_A);
    givenFilterAdmin("b3-tenant-a3", AdminType.TENANT, FILTER_TENANT_A);
    givenFilterAdmin("b3-tenant-b1", AdminType.TENANT, FILTER_TENANT_B);

    assertFilteredSearch(
        TENANT_ADMINS_SEARCH,
        "&tenantId=" + FILTER_TENANT_A,
        "b3-tenant-a1",
        "b3-tenant-a2",
        "b3-tenant-a3");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void searchAgencyAdmins_Should_filterByTenantIdAndAgencyId_When_platformAdmin() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    givenFilterAdmin("b3-agency-a1", AdminType.AGENCY, FILTER_TENANT_A, FILTER_AGENCY_X);
    givenFilterAdmin(
        "b3-agency-a2", AdminType.AGENCY, FILTER_TENANT_A, FILTER_AGENCY_X, FILTER_AGENCY_Y);
    givenFilterAdmin("b3-agency-a3", AdminType.AGENCY, FILTER_TENANT_A, FILTER_AGENCY_Y);
    givenFilterAdmin("b3-agency-b1", AdminType.AGENCY, FILTER_TENANT_B, FILTER_AGENCY_X);

    assertFilteredSearch(
        AGENCY_ADMINS_SEARCH,
        "&tenantId=" + FILTER_TENANT_A,
        "b3-agency-a1",
        "b3-agency-a2",
        "b3-agency-a3");
    assertFilteredSearch(
        AGENCY_ADMINS_SEARCH,
        "&agencyId=" + FILTER_AGENCY_X,
        "b3-agency-a1",
        "b3-agency-a2",
        "b3-agency-b1");
    assertFilteredSearch(
        AGENCY_ADMINS_SEARCH,
        "&agencyId=" + FILTER_AGENCY_X + "," + FILTER_AGENCY_Y,
        "b3-agency-a1",
        "b3-agency-a2",
        "b3-agency-a3",
        "b3-agency-b1");
    assertFilteredSearch(
        AGENCY_ADMINS_SEARCH,
        "&tenantId=" + FILTER_TENANT_A + "&agencyId=" + FILTER_AGENCY_Y,
        "b3-agency-a2",
        "b3-agency-a3");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN, AuthorityValue.RESTRICTED_AGENCY_ADMIN})
  void searchAgencyAdmins_Should_notWidenScope_When_bstAdminFiltersByForeignAgency()
      throws Exception {
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("b3-agency-a1");
    givenFilterAdmin("b3-agency-a1", AdminType.AGENCY, FILTER_TENANT_A, FILTER_AGENCY_X);
    givenFilterAdmin("b3-agency-a3", AdminType.AGENCY, FILTER_TENANT_A, FILTER_AGENCY_Y);

    // Single-tenant here; the tenant-bound cases live in UserAdminIdScopeIT (multi-tenant).
    assertFilteredSearch(AGENCY_ADMINS_SEARCH, "&agencyId=" + FILTER_AGENCY_Y);
    assertFilteredSearch(
        AGENCY_ADMINS_SEARCH,
        "&agencyId=" + FILTER_AGENCY_X + "," + FILTER_AGENCY_Y,
        "b3-agency-a1");
  }

  private void givenFilterAdmin(String id, AdminType type, Long tenantId, Long... agencyIds) {
    var admin =
        adminRepository.saveAndFlush(
            Admin.builder()
                .id(id)
                .type(type)
                .tenantId(tenantId)
                .username(id)
                .firstName("First")
                .lastName("Last")
                .email(FILTER_PROBE + "-" + id + "@example.com")
                .build());
    for (Long agencyId : agencyIds) {
      adminAgencyRepository.save(AdminAgency.builder().admin(admin).agencyId(agencyId).build());
    }
  }

  /**
   * Walks every page (two per page) of the probe search with the given filter and asserts that
   * exactly the expected ids come back, each once, and that {@code total} is the filtered count.
   */
  private void assertFilteredSearch(String path, String filter, String... expectedIds)
      throws Exception {
    var ids = new ArrayList<String>();
    var perPage = 2;
    var pages = Math.max(1, (expectedIds.length + perPage - 1) / perPage);
    for (var page = 1; page <= pages; page++) {
      MvcResult mvcResult =
          this.mockMvc
              .perform(
                  get(
                      path
                          + "?query="
                          + FILTER_PROBE
                          + "&page="
                          + page
                          + "&perPage="
                          + perPage
                          + "&field=FIRSTNAME&order=ASC"
                          + filter))
              .andExpect(status().isOk())
              .andReturn();
      var body = mvcResult.getResponse().getContentAsString();
      assertThat((Integer) JsonPath.read(body, "$.total"))
          .as("total for %s page %d", filter, page)
          .isEqualTo(expectedIds.length);
      if (expectedIds.length > 0) {
        ids.addAll(JsonPath.<List<String>>read(body, "$._embedded[*]._embedded.id"));
      }
    }
    assertThat(ids).as("ids for %s", filter).containsExactly(expectedIds);
  }

  private void assertAllElementsAreOfAdminType(JSONArray embedded, AdminType adminType) {
    for (int i = 0; i < PAGE_SIZE; i++) {
      assertAllElementsAreOfAdminType(embedded, PAGE_SIZE, adminType);
    }
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void searchTenantAdmin_Should_returnCorrectResult_When_tenantIdIsProvided() throws Exception {
    // #968: the infix search is scoped to the caller's tenant for every non-platform caller.
    // The expected hit, cgenney5, is in tenant 102, so a caller in that tenant still finds it —
    // this keeps the test on the scoped path instead of the platform-admin short-circuit.
    when(authenticatedUser.getTenantId()).thenReturn(102L);
    final String tenantId = "102";
    final String expectedAdminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";
    final String expectedUsername = "cgenney5";
    final String expectedFirstname = "Ceil";
    final String expectedLastname = "Genney";
    final String expectedEmail = "cgenney5@imageshack.us";
    when(tenantService.getRestrictedTenantData(Mockito.anyLong()))
        .thenReturn(new RestrictedTenantDTO().subdomain("subdomain").name("name"));
    // when, then
    MvcResult mvcResult =
        this.mockMvc
            .perform(
                get(
                    "/useradmin/tenantadmins/search?query="
                        + tenantId
                        + "&page=1&perPage=10&order=ASC&field=FIRSTNAME"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("_embedded", hasSize(1)))
            .andExpect(jsonPath("_embedded[0]._embedded.id").value(expectedAdminId))
            .andExpect(jsonPath("_embedded[0]._embedded.username").value(expectedUsername))
            .andExpect(jsonPath("_embedded[0]._embedded.firstname").value(expectedFirstname))
            .andExpect(jsonPath("_embedded[0]._embedded.lastname").value(expectedLastname))
            .andExpect(jsonPath("_embedded[0]._embedded.email").value(expectedEmail))
            .andReturn();

    String contentAsString = mvcResult.getResponse().getContentAsString();
    JSONArray embedded = JsonPath.read(contentAsString, "_embedded");

    assertAllElementsAreOfAdminType(embedded, 1, AdminType.TENANT);
  }

  private void assertAllElementsAreOfAdminType(
      JSONArray embedded, int pageSize, AdminType adminType) {
    for (int i = 0; i < pageSize; i++) {
      String tenantId = extractTenantWithOrderInList(embedded, i).get("id");
      assertThat(adminRepository.findByIdAndType(tenantId, adminType)).isPresent();
    }
  }

  private static LinkedHashMap<String, String> extractTenantWithOrderInList(
      JSONArray embedded, int i) {
    return (LinkedHashMap<String, String>) ((LinkedHashMap) embedded.get(i)).get("_embedded");
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void
      getTenantAdmin_Should_returnForbidden_When_attemptedToGetTenantAdminWithoutTenantAdminAuthority()
          throws Exception {
    // given
    var existingAdminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";

    // when, then
    this.mockMvc
        .perform(get(TENANT_ADMIN_PATH + "/" + existingAdminId))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_ADMIN})
  void
      deleteTenantAdmin_Should_returnForbidden_When_attemptedToDeleteTenantAdminWithoutTenantAdminAuthority()
          throws Exception {
    // given
    var existingAdminId = "6584f4a9-a7f0-42f0-b929-ab5c99c0802d";

    // when, then
    this.mockMvc
        .perform(delete(TENANT_ADMIN_PATH + "/" + existingAdminId))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @WithMockUser(authorities = {AuthorityValue.TENANT_ADMIN})
  void deleteTenantAdmin_Should_delete_When_attemptedToDeleteTenantAdminWithTenantAdminAuthority()
      throws Exception {
    // given
    // #968: the admin created here has a null tenant (multitenancy.enabled=false), which no
    // caller tenant can match, so only a platform admin can delete it in this setup.
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    var adminId = givenNewTenantAdminIsCreated();

    // when
    this.mockMvc.perform(delete(TENANT_ADMIN_PATH + "/" + adminId)).andExpect(status().isOk());

    // then
    this.mockMvc.perform(get(TENANT_ADMIN_PATH + "/" + adminId)).andExpect(status().isNoContent());
  }

  private String givenNewTenantAdminIsCreated() throws Exception {
    CreateAdminDTO createAdminDTO = new EasyRandom().nextObject(CreateAdminDTO.class);
    createAdminDTO.setEmail("valid@email.com");
    createAdminDTO.setTenantId(1);
    givenCurrentSetupIdentity(
        createAdminDTO.getUsername(),
        createAdminDTO.getEmail(),
        AccountInviteTargetRole.TENANT_ADMIN,
        false);

    MvcResult result =
        this.mockMvc
            .perform(
                post(TENANT_ADMIN_PATH)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createAdminDTO)))
            .andExpect(status().isOk())
            .andReturn();
    String content = result.getResponse().getContentAsString();
    assertIssuedSetup(AccountInviteTargetRole.TENANT_ADMIN, 1L);
    return JsonPath.read(content, "_embedded.id");
  }

  private void givenVerifiedHuman(String id, Long tenant, List<String> roles) {
    var previous =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (previous == null) return;
    if (lifecycle.snapshot(id).isEmpty()) {
      var capturedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      lifecycle.assignAtCreation(id, tenant, 24, 0, capturedAt);
      fixtureLifecycleRows.put(id, capturedAt);
    }
    var token =
        Jwt.withTokenValue("synthetic-human-session")
            .header("alg", "RS256")
            .subject(id)
            .claim("azp", "app")
            .claim("tenantId", tenant == null ? null : tenant.toString())
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    TestSecurityContextHolder.setAuthentication(
        new JwtAuthenticationToken(token, previous.getAuthorities()));
    adminRepository
        .findById(id)
        .ifPresent(
            admin ->
                identityProvider.seed(
                    new de.caritas.cob.userservice.api.adapters.keycloak.commands
                        .KeycloakTaskCommands.AccountProjection(
                        id,
                        new UsernameTranscoder().decodeUsername(admin.getUsername()),
                        admin.getEmail(),
                        admin.getFirstName(),
                        admin.getLastName(),
                        admin.getTenantId(),
                        "de",
                        true,
                        false,
                        roles,
                        false)));
  }
}
