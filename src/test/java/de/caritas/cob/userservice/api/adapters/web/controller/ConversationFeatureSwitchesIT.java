package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

/**
 * Feature switches an admin sets per Träger and Beratungsstelle (ORISO-UserService#1564), through
 * the real controllers and Spring Security: a switched-off feature is refused by the server, not
 * only hidden by the counselling app.
 *
 * <p>The Träger value is read fresh from the TenantService, the Beratungsstelle value is what the
 * AgencyService serves as effective. A switch nobody has set is on (as in the app); a switch that
 * cannot be read is never taken as on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@Transactional
class ConversationFeatureSwitchesIT {

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", "test");
  private static final long TENANT_ID = 8_201L;
  private static final long AGENCY_ID = 876_599L;
  private static final String ROOM_ID = "!feature-switches:example";
  private static final String ASKER_MATRIX_ID = "@feature-asker:example";

  @Autowired private MockMvc mvc;
  @Autowired private EntityManager entityManager;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private EffectiveAgencySettingsLookup effectiveAgencySettings;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  private Consultant owner;
  private User asker;
  private Session session;

  @BeforeEach
  void setUp() {
    traegerSettings(settings -> {});
    agencySettings(settings -> {});

    owner =
        inOneAgency(
            StreamSupport.stream(consultantRepository.findAll().spliterator(), false)
                .filter(c -> c.getDeleteDate() == null)
                .findFirst()
                .orElseThrow());
    asker =
        StreamSupport.stream(userRepository.findAll().spliterator(), false)
            .filter(u -> u.getDeleteDate() == null)
            .findFirst()
            .orElseThrow();
    asker.setTenantId(TENANT_ID);
    asker.setMatrixUserId(ASKER_MATRIX_ID);
    userRepository.save(asker);

    session =
        StreamSupport.stream(sessionRepository.findAll().spliterator(), false)
            .findFirst()
            .orElseThrow();
    session.setUser(asker);
    session.setConsultant(owner);
    session.setTenantId(TENANT_ID);
    session.setAgencyId(AGENCY_ID);
    session.setMatrixRoomId(ROOM_ID);
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    sessionRepository.save(session);
    entityManager.flush();

    when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("matrix-token");
    when(matrixSynapseService.uploadFile(any(), anyString(), anyString()))
        .thenReturn(Map.of("success", true, "content_uri", "mxc://example/abc"));
  }

  // ---- supervision: opening it for a case

  @Test
  void supervisionSwitchedOffForTheTraeger_Should_BeRefused_WithAReasonTheAppCanShow()
      throws Exception {
    traegerSettings(settings -> settings.setFeatureSupervisionEnabled(false));

    addSupervisor()
        .andExpect(status().isForbidden())
        .andExpect(header().string("X-Reason", "FEATURE_DISABLED"));

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void supervisionSwitchedOffForOneOnOneChats_Should_BeRefused_WhileTheMasterIsOn()
      throws Exception {
    traegerSettings(
        settings -> {
          settings.setFeatureSupervisionEnabled(true);
          settings.setFeatureSupervisionOneOnOneChatsEnabled(false);
        });

    addSupervisor().andExpect(status().isForbidden());
  }

  @Test
  void supervisionSwitchedOffForTheBeratungsstelle_Should_BeRefused_WhileTheTraegerAllows()
      throws Exception {
    traegerSettings(settings -> settings.setFeatureSupervisionEnabled(true));
    agencySettings(settings -> settings.setFeatureSupervisionEnabled(false));

    addSupervisor().andExpect(status().isForbidden());
  }

  /** The Träger value is read fresh: the AgencyService may still serve what it cached. */
  @Test
  void supervisionSwitchedOffForTheTraegerJustNow_Should_BeRefused_EvenIfTheAgencyStillServesOn()
      throws Exception {
    traegerSettings(settings -> settings.setFeatureSupervisionEnabled(false));
    agencySettings(settings -> settings.setFeatureSupervisionEnabled(true));

    addSupervisor().andExpect(status().isForbidden());
  }

  @Test
  void supervisionNobodyHasSwitched_Should_PassTheSwitchCheck() throws Exception {
    // The colleague does not exist: the request gets past the switches and fails there (404).
    addSupervisor().andExpect(status().isNotFound());
  }

  @Test
  void supervisionSwitchedOn_Should_PassTheSwitchCheck() throws Exception {
    traegerSettings(
        settings -> {
          settings.setFeatureSupervisionEnabled(true);
          settings.setFeatureSupervisionOneOnOneChatsEnabled(true);
        });
    agencySettings(settings -> settings.setFeatureSupervisionEnabled(true));

    addSupervisor().andExpect(status().isNotFound());
  }

  @Test
  void supervisionWhileTheTenantServiceIsDown_Should_AnswerUnavailable_NotAllow() throws Exception {
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(new ResourceAccessException("tenant service down"));

    addSupervisor().andExpect(status().isServiceUnavailable());
  }

  @Test
  void supervisionWhenTheTraegerServesNoSettings_Should_BeRefused() throws Exception {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID));

    addSupervisor().andExpect(status().isForbidden());
  }

  @Test
  void supervisionWhenTheBeratungsstelleIsUnknown_Should_BeRefused() throws Exception {
    when(effectiveAgencySettings.findEffectiveSettings(anyLong(), anyLong()))
        .thenReturn(Optional.empty());

    addSupervisor().andExpect(status().isForbidden());
  }

  // ---- attachments: uploading a file into a conversation

  @Test
  void uploadSwitchedOffForTheTraeger_Should_BeRefused_AndNothingReachesMatrix() throws Exception {
    traegerSettings(settings -> settings.setFeatureMediaUploadEnabled(false));

    upload()
        .andExpect(status().isForbidden())
        .andExpect(header().string("X-Reason", "FEATURE_DISABLED"));

    verify(matrixSynapseService, never()).uploadFile(any(), anyString(), anyString());
  }

  @Test
  void uploadSwitchedOffForOneOnOneChats_Should_BeRefused_WhileTheMasterIsOn() throws Exception {
    traegerSettings(
        settings -> {
          settings.setFeatureMediaUploadEnabled(true);
          settings.setFeatureMediaUploadOneOnOneChatsEnabled(false);
        });

    upload().andExpect(status().isForbidden());

    verify(matrixSynapseService, never()).uploadFile(any(), anyString(), anyString());
  }

  @Test
  void uploadSwitchedOffForTheBeratungsstelle_Should_BeRefused_WhileTheTraegerAllows()
      throws Exception {
    agencySettings(settings -> settings.setFeatureMediaUploadOneOnOneChatsEnabled(false));

    upload().andExpect(status().isForbidden());

    verify(matrixSynapseService, never()).uploadFile(any(), anyString(), anyString());
  }

  @Test
  void uploadNobodyHasSwitched_Should_Work() throws Exception {
    upload().andExpect(status().isOk());

    verify(matrixSynapseService).uploadFile(any(), anyString(), anyString());
  }

  @Test
  void uploadSwitchedOn_Should_Work() throws Exception {
    traegerSettings(
        settings -> {
          settings.setFeatureMediaUploadEnabled(true);
          settings.setFeatureMediaUploadOneOnOneChatsEnabled(true);
        });
    agencySettings(settings -> settings.setFeatureMediaUploadEnabled(true));

    upload().andExpect(status().isOk());
  }

  @Test
  void uploadWhileTheTenantServiceIsDown_Should_AnswerUnavailable_AndNothingReachesMatrix()
      throws Exception {
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(new ResourceAccessException("tenant service down"));

    upload().andExpect(status().isServiceUnavailable());

    verify(matrixSynapseService, never()).uploadFile(any(), anyString(), anyString());
  }

  // ---- helpers

  private ResultActions addSupervisor() throws Exception {
    actAsConsultant(owner);
    return mvc.perform(
        signed(
            post("/users/sessions/{id}/supervisors", session.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supervisorConsultantId\":\"no-such-colleague\"}"),
            AuthorityValue.CONSULTANT_DEFAULT));
  }

  private ResultActions upload() throws Exception {
    actAsAsker();
    return mvc.perform(
        signed(
            multipart("/matrix/sessions/{id}/upload", session.getId())
                .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2})),
            AuthorityValue.USER_DEFAULT));
  }

  /** What the TenantService serves for the Träger; nothing set means "nobody switched it". */
  private void traegerSettings(
      Consumer<de.caritas.cob.userservice.tenantservice.generated.web.model.Settings> change) {
    var settings = new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings();
    change.accept(settings);
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(new RestrictedTenantDTO().id(TENANT_ID).settings(settings));
  }

  /** What the AgencyService serves as the effective settings of the Beratungsstelle. */
  private void agencySettings(Consumer<Settings> change) {
    var settings = new Settings();
    change.accept(settings);
    when(effectiveAgencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(settings));
  }

  private Consultant inOneAgency(Consultant consultant) {
    consultantAgencyRepository.deleteAll(
        consultantAgencyRepository.findByConsultantId(consultant.getId()));
    consultant.setTenantId(TENANT_ID);
    var membership =
        consultantAgencyRepository.save(
            ConsultantAgency.builder()
                .consultant(consultant)
                .agencyId(AGENCY_ID)
                .tenantId(TENANT_ID)
                .build());
    var memberships = new HashSet<ConsultantAgency>();
    memberships.add(membership);
    consultant.setConsultantAgencies(memberships);
    return consultantRepository.save(consultant);
  }

  private void actAsConsultant(Consultant consultant) {
    when(authenticatedUser.getUserId()).thenReturn(consultant.getId());
    when(authenticatedUser.getUsername()).thenReturn(consultant.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
  }

  private void actAsAsker() {
    when(authenticatedUser.getUserId()).thenReturn(asker.getUserId());
    when(authenticatedUser.getUsername()).thenReturn(asker.getUsername());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B signed(
      B request, String authority) {
    return request
        .with(
            jwt()
                .authorities(new SimpleGrantedAuthority(authority))
                .jwt(token -> token.claim("userId", authenticatedUser.getUserId())))
        .cookie(CSRF_COOKIE)
        .header("X-CSRF-Token", "test");
  }
}
