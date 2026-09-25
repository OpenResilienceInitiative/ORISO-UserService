package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLocaleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.GroupChatDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RegistrationDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.RegistrationMandatoryFieldsDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.SessionDataInitializingDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.WelcomeMessageDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Every real way into the platform must still create its rows in a tenant. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"multitenancy.enabled=true"})
class MultiTenantRegistrationIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  private static final long TENANT = 2L;
  private static final long AGENCY = 9901L;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;

  @MockitoBean(
      extraInterfaces = {
        IdentityAccountRemover.class,
        IdentityAuthentication.class,
        IdentityDeactivator.class,
        IdentityDummyEmailUpdater.class,
        IdentityEmailAddressUpdater.class,
        IdentityEmailOwnerLookup.class,
        IdentityLocaleLookup.class,
        IdentityPasswordUpdater.class,
        IdentityProfileLookup.class,
        IdentityProfileUpdater.class,
        IdentityRoleLookup.class,
        IdentityRoleUpdater.class,
        IdentitySecondFactor.class,
        IdentityUsernameAvailability.class
      })
  IdentityClient identityClient;

  @MockitoBean TenantService tenantService;
  @MockitoBean AgencyService agencyService;
  @MockitoBean ConsultingTypeManager consultingTypeManager;
  @MockitoBean MatrixSynapseService matrixSynapseService;

  @MockitoBean TenantResolverService tenantResolverService;
  @MockitoBean ConsultantAdminFacade consultantAdminFacade;
  @MockitoBean ConsultantAgencyRelationCreatorService consultantAgencyRelationCreatorService;
  @MockitoBean TenantCreationClient tenantCreationClient;
  @MockitoBean OperatorDpaContentClient operatorDpaContentClient;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser caller;

  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AgencyInviteLinkRepository agencyInviteLinkRepository;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private UserChatRepository userChatRepository;

  private final List<String> createdUserIds = new ArrayList<>();
  private final List<Runnable> cleanups = new ArrayList<>();

  /** The accept re-checks the agency with the service token (ORISO-Admin#1026 P2-3). */
  @MockitoBean private AgencyFacts agencyFacts;

  @BeforeEach
  void oneAgencyOfTenantTwo() throws Exception {
    when(agencyFacts.find(anyLong()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new AgencyFacts.Agency(invocation.getArgument(0), null, false, List.of())));
    // The platform domain resolves to the main tenant, as with single-domain multitenancy.
    when(tenantResolverService.resolve(any())).thenReturn(1L);
    when(((IdentityAuthentication) identityClient).login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("access", 300, 1800, "refresh"));
    when(((IdentityDummyEmailUpdater) identityClient).updateDummyEmail(anyString(), any()))
        .thenAnswer(call -> call.getArgument(0) + "@dummy.synthetic.oriso.test");
    when(agencyService.getAgenciesByConsultingType(any(Integer.class)))
        .thenAnswer(call -> List.of(agencyService.getAgency(AGENCY)));
    var matrixUser = new MatrixCreateUserResponseDTO();
    matrixUser.setUserId("@registration:synthetic.oriso.test");
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(matrixUser));
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(ChatRecoveryPolicyFixtures.tenant().id(TENANT));
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenReturn(new RestrictedTenantDTO().id(TENANT).subdomain("synthetic"));
    var agency =
        new AgencyDTO()
            .id(AGENCY)
            .tenantId(TENANT)
            .consultingType(1)
            .teamAgency(false)
            .offline(false);
    when(agencyService.getAgency(AGENCY)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency);
    when(agencyService.getAgencies(any())).thenReturn(List.of(agency));
    var consultingType =
        new ExtendedConsultingTypeResponseDTO()
            .id(1)
            .isAnonymousConversationAllowed(true)
            .groupChat(new GroupChatDTO().isGroupChat(false))
            .consultantBoundedToConsultingType(false)
            .welcomeMessage(new WelcomeMessageDTO().sendWelcomeMessage(false))
            .sendFurtherStepsMessage(false)
            .sessionDataInitializing(new SessionDataInitializingDTO().age(false).state(false))
            .languageFormal(false)
            .registration(
                new RegistrationDTO()
                    .mandatoryFields(new RegistrationMandatoryFieldsDTO().age(false).state(false)));
    when(consultingTypeManager.getConsultingTypeSettings(any(Integer.class)))
        .thenReturn(consultingType);
    when(consultingTypeManager.getConsultingTypeSettings(anyString())).thenReturn(consultingType);
    when(((IdentityUsernameAvailability) identityClient).isUsernameAvailable(anyString()))
        .thenReturn(true);
    when(identityClient.createUser(any()))
        .thenAnswer(
            call -> {
              var identity = new CreatedIdentity();
              identity.setUserId(UUID.randomUUID().toString());
              createdUserIds.add(identity.getUserId());
              return identity;
            });
  }

  @AfterEach
  void removeCreatedRows() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      cleanups.forEach(Runnable::run);
      for (var userId : createdUserIds) {
        sessionRepository.findByUserUserId(userId).forEach(sessionRepository::delete);
        userRepository.findById(userId).ifPresent(userRepository::delete);
      }
    } finally {
      TenantContext.clear();
    }
  }

  // --- advice seeker, agency chosen by postcode or topic -----------------------------------------

  @Test
  void registerAdviceSeeker_Should_CreateUserAndSessionInTheAgencysTenant() throws Exception {
    var result = mockMvc.perform(register(Map.of())).andReturn();

    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(201);
    assertCreatedInTenant();
  }

  @Test
  void registerAdviceSeeker_Should_CreateUserAndSessionInTheAgencysTenant_When_TopicChosen()
      throws Exception {
    var result =
        mockMvc.perform(register(Map.of("mainTopicId", 1, "topicIds", List.of(1)))).andReturn();

    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(201);
    assertCreatedInTenant();
  }

  @Test
  void registerAdviceSeeker_Should_WriteNothing_When_AgencyHasNoTenant() throws Exception {
    var agencyWithoutTenant =
        new AgencyDTO().id(AGENCY).consultingType(1).teamAgency(false).offline(false);
    when(agencyService.getAgency(AGENCY)).thenReturn(agencyWithoutTenant);
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agencyWithoutTenant);

    var result = mockMvc.perform(register(Map.of())).andReturn();

    assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      assertThat(createdUserIds).noneMatch(id -> userRepository.findById(id).isPresent());
    } finally {
      TenantContext.clear();
    }
  }

  // --- live-chat guest --------------------------------------------------------------------------

  @Test
  void redeemLiveChatLink_Should_CreateGuestAndSessionInTheLinksTenant() throws Exception {
    var link = persistInviteLink("LIVE_CHAT");

    var result =
        mockMvc
            .perform(
                post("/users/invitelinks/{token}/redeem", link.getToken())
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE))
            .andReturn();

    assertStatus(result, 200);
    assertCreatedInTenant();
  }

  // --- group-chat invite link: registration with the link's agency, then joining the chat ------

  @Test
  void groupInviteLink_Should_LetTheNewAdviceSeekerJoinTheChat() throws Exception {
    var link = persistInviteLink("TENANT_CHAT");
    assertStatus(mockMvc.perform(register(Map.of())).andReturn(), 201);
    var chat = persistChat();
    actAsAdviceSeeker(createdUserIds.get(0));

    var result =
        mockMvc
            .perform(
                put("/users/chat/{chatId}/assign", chat.getId())
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.user(createdUserIds.get(0))
                            .authorities(
                                new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT)))
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE))
            .andReturn();

    assertStatus(result, 200);
    TenantContext.setCurrentTenant(TENANT);
    try {
      assertThat(
              java.util.stream.StreamSupport.stream(
                      userChatRepository.findAll().spliterator(), false)
                  .toList())
          .anyMatch(
              relation ->
                  relation.getChat().getId().equals(chat.getId())
                      && relation.getUser().getUserId().equals(createdUserIds.get(0)));
    } finally {
      TenantContext.clear();
    }
    assertThat(link.getTenantId()).isEqualTo(TENANT);
  }

  // --- account invites --------------------------------------------------------------------------

  @Test
  void acceptCounsellorInvite_Should_CreateTheCounsellorInTheInvitesTenant() throws Exception {
    var tenantAtCreation = new java.util.concurrent.atomic.AtomicReference<Long>();
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenAnswer(
            call -> {
              tenantAtCreation.set(TenantContext.getCurrentTenant());
              return new ConsultantAdminResponseDTO()
                  .embedded(new ConsultantDTO().id(UUID.randomUUID().toString()));
            });
    var token = persistAccountInvite(AccountInviteTargetRole.COUNSELLOR);

    var result =
        mockMvc
            .perform(
                post("/users/account-invites/{token}/accept", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"invited_counsellor_"
                            + RandomStringUtils.randomAlphabetic(6)
                            + "\",\"password\":\"Valid-Test-Password-2026!\",\"formalLanguage\":true}"))
            .andReturn();

    assertStatus(result, 200);
    assertThat(tenantAtCreation.get()).isEqualTo(TENANT);
  }

  @Test
  void registerTenantAdminFromInvite_Should_CreateTheAdminInTheInvitesTenant() throws Exception {
    when(operatorDpaContentClient.fetchPublishedDpa())
        .thenReturn(new OperatorDpaContentClient.OperatorDpa("dpa", "1"));
    when(((IdentitySecondFactor) identityClient).getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(false, "SECRET", "QR", null));
    when(identityClient.createUser(any(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              var identity = new CreatedIdentity();
              identity.setUserId(UUID.randomUUID().toString());
              cleanups.add(() -> adminRepository.deleteById(identity.getUserId()));
              return identity;
            });
    var token = persistAccountInvite(AccountInviteTargetRole.TENANT_ADMIN);

    var result =
        mockMvc
            .perform(
                post("/users/account-invites/{token}/onboarding/register", token)
                    .cookie(CSRF_COOKIE)
                    .header(CSRF_HEADER, CSRF_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"organisation\":{\"name\":\"Synthetic Träger\",\"subdomain\":\"synthetic\"},"
                            + "\"dpa\":{\"accepted\":true,\"signerName\":\"S\","
                            + "\"signerPosition\":\"CEO\",\"signerEmail\":\"s@synthetic.oriso.test\"},"
                            + "\"account\":{\"password\":\"Valid-Test-Password-2026!\"},"
                            + "\"reservedTenantId\":"
                            + TENANT
                            + ",\"tenantIdReservationToken\":\"reservation\"}"))
            .andReturn();

    assertStatus(result, 200);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      var invite =
          java.util.stream.StreamSupport.stream(
                  accountInviteRepository.findAll().spliterator(), false)
              .filter(row -> AccountInviteService.hash(token).equals(row.getTokenHash()))
              .findFirst();
      var admin = adminRepository.findById(invite.orElseThrow().getAcceptedByUserId());
      assertThat(admin.orElseThrow().getTenantId()).isEqualTo(TENANT);
    } finally {
      TenantContext.clear();
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
      Map<String, Object> extra) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("username", "reg" + RandomStringUtils.randomAlphabetic(8));
    body.put("password", "Synthetic-Pass-1!");
    body.put("postcode", "12345");
    body.put(
        "email", RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@synthetic.oriso.test");
    body.put("agencyId", AGENCY);
    body.put("termsAccepted", "true");
    body.put("consultingType", "1");
    body.put("preferredLanguage", "de");
    body.putAll(extra);
    return post("/users/askers/new")
        .cookie(CSRF_COOKIE)
        .header(CSRF_HEADER, CSRF_VALUE)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private AgencyInviteLink persistInviteLink(String chatType) {
    var link =
        agencyInviteLinkRepository.save(
            AgencyInviteLink.builder()
                .token("registration-" + UUID.randomUUID())
                .tenantId(TENANT)
                .agencyId(AGENCY)
                .consultingTypeId(1)
                .linkKind("TENANT")
                .chatType(chatType)
                .anonymity("FULL")
                .createdByUserId("tenant-admin")
                .createDate(java.time.LocalDateTime.now())
                .status("ACTIVE")
                .build());
    cleanups.add(() -> agencyInviteLinkRepository.deleteById(link.getId()));
    return link;
  }

  private String persistAccountInvite(AccountInviteTargetRole role) {
    var token = "registration-" + UUID.randomUUID();
    var invite =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(role)
                .tenantId(TENANT)
                .tenantIdReservationToken("reservation")
                .recipientEmail(
                    RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@synthetic.oriso.test")
                .firstName("Synthetic")
                .lastName("Invitee")
                .agencyId(role == AccountInviteTargetRole.COUNSELLOR ? AGENCY : null)
                .departmentId(role == AccountInviteTargetRole.COUNSELLOR ? 2L : null)
                .tokenHash(AccountInviteService.hash(token))
                .expiresAt(java.time.LocalDateTime.now().plusDays(1))
                .status(AccountInviteStatus.EMAIL_SENT)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(java.time.LocalDateTime.now())
                .build());
    cleanups.add(() -> accountInviteRepository.deleteById(invite.getId()));
    return token;
  }

  private Chat persistChat() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      var owner = consultantRepository.findAll().iterator().next();
      var chat =
          chatRepository.save(
              Chat.builder()
                  .topic("Synthetic group")
                  .consultingTypeId(1)
                  .initialStartDate(java.time.LocalDateTime.now())
                  .startDate(java.time.LocalDateTime.now())
                  .duration(60)
                  .repetitive(false)
                  .active(true)
                  .chatOwner(owner)
                  .matrixRoomId("!group-invite:synthetic.oriso.test")
                  .createDate(java.time.LocalDateTime.now())
                  .updateDate(java.time.LocalDateTime.now())
                  .build());
      cleanups.add(
          () -> {
            java.util.stream.StreamSupport.stream(userChatRepository.findAll().spliterator(), false)
                .filter(relation -> relation.getChat().getId().equals(chat.getId()))
                .forEach(userChatRepository::delete);
            chatRepository.deleteById(chat.getId());
          });
      return chat;
    } finally {
      TenantContext.clear();
    }
  }

  private void actAsAdviceSeeker(String userId) {
    when(tenantResolverService.resolve(any())).thenReturn(TENANT);
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(TENANT);
    caller.setRoles(java.util.Set.of(UserRole.USER.getValue()));
    caller.setGrantedAuthorities(java.util.Set.of(AuthorityValue.USER_DEFAULT));
  }

  private void assertCreatedInTenant() {
    assertThat(createdUserIds).hasSize(1);
    TenantContext.setCurrentTenant(TENANT);
    try {
      User user = userRepository.findById(createdUserIds.get(0)).orElseThrow();
      assertThat(user.getTenantId()).isEqualTo(TENANT);
      List<Session> sessions = sessionRepository.findByUserUserId(user.getUserId());
      assertThat(sessions).isNotEmpty().allMatch(s -> Long.valueOf(TENANT).equals(s.getTenantId()));
    } finally {
      TenantContext.clear();
    }
  }

  private static void assertStatus(MvcResult result, int expected) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
  }
}
