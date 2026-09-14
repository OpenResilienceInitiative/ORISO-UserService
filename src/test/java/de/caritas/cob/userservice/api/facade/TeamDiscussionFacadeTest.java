package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.controller.TeamDiscussionController;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiDefaultResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.config.auth.SecurityConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionCreationWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionParticipantWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionRoomCleanupService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** US#473 / ADR-016 — Team-Besprechung lifecycle. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamDiscussionFacadeTest {

  private static final Long SESSION_ID = 42L;
  private static final Long AGENCY_ID = 7L;
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String ROOM_ID = "!discussion:oriso";

  private TeamDiscussionFacade facade;

  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private TeamDiscussionRepository teamDiscussionRepository;
  @Mock private TeamDiscussionParticipantRepository participantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private AgencyMatrixCredentialClient matrixCredentialClient;
  @Mock private TeamDiscussionFeatureGate featureGate;
  @Mock private TeamDiscussionRoomCleanupService roomCleanupService;
  @Mock private AuthenticatedUser authenticatedUser;

  private Session session;
  private Consultant consultant;

  @BeforeEach
  void setUp() throws Exception {
    facade =
        new TeamDiscussionFacade(
            sessionRepository,
            consultantRepository,
            consultantAgencyRepository,
            teamDiscussionRepository,
            matrixSynapseService,
            matrixCredentialClient,
            featureGate,
            new TeamDiscussionCreationWriter(teamDiscussionRepository),
            new TeamDiscussionParticipantWriter(participantRepository),
            roomCleanupService);
    session = new Session();
    session.setId(SESSION_ID);
    session.setAgencyId(AGENCY_ID);
    session.setStatus(SessionStatus.NEW);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setEnquiryMessageDate(java.time.LocalDateTime.now());
    session.setTenantId(3L);

    consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId("@consultant1:oriso");

    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(consultantRepository.findById(CONSULTANT_ID)).thenReturn(Optional.of(consultant));
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(true);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
    when(teamDiscussionRepository.saveAndFlush(any(TeamDiscussion.class)))
        .thenAnswer(
            invocation -> {
              TeamDiscussion d = invocation.getArgument(0);
              d.setId(99L);
              return d;
            });

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency7:oriso");
    credentials.setMatrixPassword("secret");
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenReturn(Optional.of(credentials));
    when(matrixSynapseService.loginUser("agency7", "secret")).thenReturn("agency-token");
    when(matrixSynapseService.loginAsUserAccessToken("@consultant1:oriso"))
        .thenReturn("consultant-token");
    when(matrixSynapseService.joinRoom(ROOM_ID, "consultant-token")).thenReturn(true);
    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM_ID))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.PURGED);

    var body = new MatrixCreateRoomResponseDTO();
    body.setRoomId(ROOM_ID);
    when(matrixSynapseService.createRoom(anyString(), anyString(), eq("agency-token")))
        .thenReturn(ResponseEntity.ok(body));
  }

  @Test
  void unsentRegisteredDraftCannotStartATeamDiscussion() throws Exception {
    session.setEnquiryMessageDate(null);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    var http =
        MockMvcBuilders.standaloneSetup(new TeamDiscussionController(facade, authenticatedUser))
            .setControllerAdvice(
                new ApiResponseEntityExceptionHandler(),
                new ApiDefaultResponseEntityExceptionHandler())
            .build();
    http.perform(post("/users/sessions/{id}/team-discussion", SESSION_ID))
        .andExpect(status().isBadRequest());
  }

  @Test
  void teamDiscussionHttp_shouldRejectAskersAndForeignColleagues() throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource("test", java.util.Map.of("multitenancy.enabled", "false")));
      context.register(TeamHttpSecurityFixture.class);
      context.addBeanFactoryPostProcessor(
          factory -> {
            factory.registerSingleton(
                "teamDiscussionController",
                new TeamDiscussionController(facade, authenticatedUser));
          });
      context.refresh();
      var http =
          MockMvcBuilders.webAppContextSetup(context)
              .apply(SecurityMockMvcConfigurers.springSecurity())
              .build();
      when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
      var path = "/users/sessions/42/team-discussion";
      http.perform(
              MockMvcRequestBuilders.get(path)
                  .with(
                      SecurityMockMvcRequestPostProcessors.user("colleague")
                          .authorities(
                              new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))))
          .andExpect(status().isNoContent());
      http.perform(
              MockMvcRequestBuilders.get(path)
                  .with(
                      SecurityMockMvcRequestPostProcessors.user("asker")
                          .authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT))))
          .andExpect(status().isForbidden());
      http.perform(
              post(path)
                  .cookie(new jakarta.servlet.http.Cookie("test-csrf", "matching-token"))
                  .header("X-Test-CSRF", "matching-token")
                  .with(
                      SecurityMockMvcRequestPostProcessors.user("asker")
                          .authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT))))
          .andExpect(status().isForbidden());
      http.perform(
              post(path)
                  .cookie(new jakarta.servlet.http.Cookie("test-csrf", "matching-token"))
                  .header("X-Test-CSRF", "matching-token")
                  .with(
                      SecurityMockMvcRequestPostProcessors.user("colleague")
                          .authorities(
                              new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.matrixRoomId").value(ROOM_ID));
      when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
              CONSULTANT_ID, AGENCY_ID))
          .thenReturn(false);
      http.perform(
              MockMvcRequestBuilders.get(path)
                  .with(
                      SecurityMockMvcRequestPostProcessors.user("outsider")
                          .authorities(
                              new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_DEFAULT))))
          .andExpect(status().isForbidden());
    }
  }

  @EnableWebMvc
  @Import({SecurityConfig.class, ApiResponseEntityExceptionHandler.class})
  static class TeamHttpSecurityFixture {
    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var properties = new CsrfSecurityProperties();
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-Test-CSRF");
      whitelist.setHeader(header);
      properties.setWhitelist(whitelist);
      properties.setHeader(header);
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("test-csrf");
      properties.setCookie(cookie);
      return properties;
    }

    @Bean
    IdentityConfig identityConfig() {
      var identity = org.mockito.Mockito.mock(IdentityConfig.class);
      when(identity.getOpenIdConnectUrl("certs")).thenReturn("https://identity.invalid/certs");
      return identity;
    }

    @Bean
    RoleAuthorizationAuthorityMapper authorityMapper() {
      return org.mockito.Mockito.mock(RoleAuthorizationAuthorityMapper.class);
    }
  }

  @Test
  void openingDiscussion_shouldUseTheSameRoomWhenAColleagueCreatesItConcurrently() {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    var winningDiscussion =
        TeamDiscussion.builder()
            .id(100L)
            .sessionId(SESSION_ID)
            .matrixRoomId("!colleague-room:oriso")
            .status(TeamDiscussion.Status.OPEN)
            .tenantId(3L)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(Optional.empty(), Optional.of(winningDiscussion));
    when(teamDiscussionRepository.saveAndFlush(any(TeamDiscussion.class)))
        .thenThrow(new DataIntegrityViolationException("Concurrent session room insert"));
    when(matrixSynapseService.joinRoom("!colleague-room:oriso", "consultant-token"))
        .thenReturn(true);

    var opened = controller.getOrCreate(SESSION_ID).getBody();
    var reopened = controller.get(SESSION_ID).getBody();

    assertThat(opened.matrixRoomId()).isEqualTo("!colleague-room:oriso");
    assertThat(reopened.matrixRoomId()).isEqualTo(opened.matrixRoomId());
  }

  @Test
  void openingDiscussion_shouldReportFailedCleanupInsteadOfSuccess() {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    var winningDiscussion =
        TeamDiscussion.builder()
            .id(100L)
            .sessionId(SESSION_ID)
            .matrixRoomId("!colleague-room:oriso")
            .status(TeamDiscussion.Status.OPEN)
            .tenantId(3L)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(Optional.empty(), Optional.of(winningDiscussion));
    when(teamDiscussionRepository.saveAndFlush(any(TeamDiscussion.class)))
        .thenThrow(new DataIntegrityViolationException("Concurrent session room insert"));
    when(matrixSynapseService.joinRoom("!colleague-room:oriso", "consultant-token"))
        .thenReturn(true);

    when(matrixSynapseService.purgeRoomOrConfirmGone(ROOM_ID))
        .thenReturn(MatrixSynapseService.RoomPurgeOutcome.FAILED);
    assertThatThrownBy(() -> controller.getOrCreate(SESSION_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_GATEWAY));
    verify(roomCleanupService).recordFailedCleanup(SESSION_ID, ROOM_ID);
    assertThat(controller.get(SESSION_ID).getBody().matrixRoomId())
        .isEqualTo("!colleague-room:oriso");
  }

  @Test
  void openingDiscussion_shouldTolerateAnotherTabRecordingTheSameParticipant() {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));
    when(participantRepository.existsByTeamDiscussionIdAndConsultantId(99L, CONSULTANT_ID))
        .thenReturn(false, true);
    when(participantRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("Concurrent participant insert"));

    assertThat(controller.getOrCreate(SESSION_ID).getBody().matrixRoomId()).isEqualTo(ROOM_ID);
    assertThat(controller.getOrCreate(SESSION_ID).getBody().matrixRoomId()).isEqualTo(ROOM_ID);
  }

  @Test
  void openingDiscussion_shouldReportFailedJoinAndAllowRetry() throws Exception {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));
    when(matrixSynapseService.joinRoom(ROOM_ID, "consultant-token")).thenReturn(false, true);

    var http =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new ApiResponseEntityExceptionHandler(),
                new ApiDefaultResponseEntityExceptionHandler())
            .build();
    http.perform(post("/users/sessions/{id}/team-discussion", SESSION_ID))
        .andExpect(status().isBadGateway());
    http.perform(post("/users/sessions/{id}/team-discussion", SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matrixRoomId").value(ROOM_ID));
  }

  @Test
  void openingDiscussion_shouldReportMissingConsultantMatrixIdentityAsInternalError()
      throws Exception {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    consultant.setMatrixUserId(null);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));

    var http =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new ApiResponseEntityExceptionHandler(),
                new ApiDefaultResponseEntityExceptionHandler())
            .build();

    http.perform(post("/users/sessions/{id}/team-discussion", SESSION_ID))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void openingDiscussion_shouldReportMissingAgencyMatrixCredentialsAsInternalError()
      throws Exception {
    var controller = new TeamDiscussionController(facade, authenticatedUser);
    when(authenticatedUser.getUserId()).thenReturn(CONSULTANT_ID);
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).thenReturn(Optional.empty());

    var http =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new ApiResponseEntityExceptionHandler(),
                new ApiDefaultResponseEntityExceptionHandler())
            .build();

    http.perform(post("/users/sessions/{id}/team-discussion", SESSION_ID))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void getOrCreateDiscussion_shouldCreateRoomWithAgencyOperatorAndRecordParticipant()
      throws Exception {
    var view = facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view.matrixRoomId()).isEqualTo(ROOM_ID);
    assertThat(view.status()).isEqualTo(TeamDiscussion.Status.OPEN);
    verify(matrixSynapseService).createRoom(anyString(), anyString(), eq("agency-token"));
    verify(matrixSynapseService).inviteUserToRoom(ROOM_ID, "@consultant1:oriso", "agency-token");
    verify(matrixSynapseService).joinRoom(ROOM_ID, "consultant-token");
    verify(participantRepository).saveAndFlush(any());
  }

  @Test
  void getOrCreateDiscussion_shouldReuseExistingDiscussionWithoutCreatingRoom() throws Exception {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));

    var view = facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view.matrixRoomId()).isEqualTo(ROOM_ID);
    verify(matrixSynapseService, never()).createRoom(anyString(), anyString(), anyString());
  }

  @Test
  void getOrCreateDiscussion_shouldRejectAssignedSession() {
    session.setConsultant(consultant);

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getOrCreateDiscussion_shouldRejectForeignAgencyConsultant() {
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(false);

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void getOrCreateDiscussion_shouldRespectFeatureGate() throws Exception {
    doThrow(new ForbiddenException("Team discussion is disabled"))
        .when(featureGate)
        .requireEnabled(org.mockito.ArgumentMatchers.any());

    assertThatThrownBy(() -> facade.getOrCreateDiscussion(SESSION_ID, CONSULTANT_ID))
        .isInstanceOf(ForbiddenException.class);
    verify(matrixSynapseService, never()).createRoom(anyString(), anyString(), anyString());
  }

  @Test
  void archiveDiscussionIfPresent_shouldArchiveAndSetRoomReadOnly() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));

    facade.archiveDiscussionIfPresent(session);

    assertThat(discussion.getStatus()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(discussion.getArchiveDate()).isNotNull();
    verify(teamDiscussionRepository).save(discussion);
    verify(matrixSynapseService)
        .setRoomEventsDefaultPowerLevel(
            ROOM_ID, TeamDiscussionFacade.ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL, "agency-token");
  }

  @Test
  void archiveDiscussionIfPresent_shouldNeverThrow() {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.OPEN)
                    .build()));
    when(matrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .thenThrow(new IllegalStateException("agency service down"));

    assertThatCode(() -> facade.archiveDiscussionIfPresent(session)).doesNotThrowAnyException();
  }

  @Test
  void archiveDiscussionIfPresent_shouldIgnoreAlreadyArchived() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.ARCHIVED)
            .readOnlyApplied(true)
            .archiveDate(LocalDateTime.now().minusDays(1))
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));

    facade.archiveDiscussionIfPresent(session);

    verify(teamDiscussionRepository, never()).save(any());
    verify(matrixSynapseService, never())
        .setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString());
  }

  @Test
  void getDiscussion_shouldReturnArchivedDiscussionForReadOnlyAccess() {
    when(teamDiscussionRepository.findBySessionId(SESSION_ID))
        .thenReturn(
            Optional.of(
                TeamDiscussion.builder()
                    .id(99L)
                    .sessionId(SESSION_ID)
                    .matrixRoomId(ROOM_ID)
                    .status(TeamDiscussion.Status.ARCHIVED)
                    .archiveDate(LocalDateTime.now())
                    .build()));

    var view = facade.getDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view).isPresent();
    assertThat(view.get().status()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(view.get().archiveDate()).isNotNull();
  }

  @Test
  void getDiscussion_shouldLazilyArchiveOpenDiscussionOnAssignedSession() {
    // Review finding F2 (create/accept race): an OPEN row on an accepted case reconciles on access.
    session.setConsultant(consultant);
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));
    when(matrixSynapseService.setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString()))
        .thenReturn(true);

    var view = facade.getDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(view).isPresent();
    assertThat(view.get().status()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(discussion.getStatus()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    verify(teamDiscussionRepository, org.mockito.Mockito.atLeastOnce()).save(discussion);
  }

  @Test
  void archiveDiscussionIfPresent_shouldTrackFailedReadOnlyForRetry() {
    // Review finding F3 (archive ordering): a failed power-level call must not be forgotten.
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));
    when(matrixSynapseService.setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString()))
        .thenReturn(false);

    facade.archiveDiscussionIfPresent(session);

    assertThat(discussion.getStatus()).isEqualTo(TeamDiscussion.Status.ARCHIVED);
    assertThat(discussion.isReadOnlyApplied()).isFalse();
  }

  @Test
  void archiveDiscussionIfPresent_shouldRetryFailedLockWithoutAConsultantReopening() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.OPEN)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));
    when(matrixSynapseService.setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString()))
        .thenReturn(false, true);

    facade.archiveDiscussionIfPresent(session);
    facade.archiveDiscussionIfPresent(session);

    assertThat(discussion.isReadOnlyApplied()).isTrue();
  }

  @Test
  void getDiscussion_shouldRetryReadOnlyOnArchivedDiscussionUntilApplied() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.ARCHIVED)
            .archiveDate(LocalDateTime.now())
            .readOnlyApplied(false)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));
    when(matrixSynapseService.setRoomEventsDefaultPowerLevel(
            eq(ROOM_ID), eq(TeamDiscussionFacade.ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL), anyString()))
        .thenReturn(true);

    facade.getDiscussion(SESSION_ID, CONSULTANT_ID);

    assertThat(discussion.isReadOnlyApplied()).isTrue();
    verify(matrixSynapseService)
        .setRoomEventsDefaultPowerLevel(
            ROOM_ID, TeamDiscussionFacade.ARCHIVED_EVENTS_DEFAULT_POWER_LEVEL, "agency-token");
    verify(teamDiscussionRepository).save(discussion);
  }

  @Test
  void getDiscussion_shouldNotRetryReadOnlyWhenAlreadyApplied() {
    var discussion =
        TeamDiscussion.builder()
            .id(99L)
            .sessionId(SESSION_ID)
            .matrixRoomId(ROOM_ID)
            .status(TeamDiscussion.Status.ARCHIVED)
            .archiveDate(LocalDateTime.now())
            .readOnlyApplied(true)
            .build();
    when(teamDiscussionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(discussion));

    facade.getDiscussion(SESSION_ID, CONSULTANT_ID);

    verify(matrixSynapseService, never())
        .setRoomEventsDefaultPowerLevel(anyString(), anyInt(), anyString());
  }
}
