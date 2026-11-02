package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantEnquiryOpenedRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** PUT /users/consultants/sessions/{id}/enquiry/opened (UserService#1549). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class EnquiryOpenedControllerIT {

  // Seed data: both are counsellors of agency 1; the third one only belongs to agency 0.
  private static final String COUNSELLOR_A = "473f7c4b-f011-4fc2-847c-ceb636a5b399";
  private static final String COUNSELLOR_B = "88613f5d-0d40-47e0-b323-e792e7fba3ed";
  private static final String COUNSELLOR_OTHER_AGENCY = "75abe824-fb42-476d-a52a-66660113bdcc";
  private static final String SEEKER_USER_ID = "1da238c6-cd46-4162-80f1-bff74eafe77f";
  private static final long AGENCY = 1L;

  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ConsultantEnquiryOpenedRepository openedRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager entityManager;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private AgencyService agencyService;

  private void callerIs(String consultantId) {
    when(authenticatedUser.getUserId()).thenReturn(consultantId);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
  }

  private Long givenSession(
      SessionStatus status, RegistrationType type, long agencyId, String consultantId) {
    var session =
        new Session(
            userRepository.findById(SEEKER_USER_ID).orElseThrow(),
            1,
            "12345",
            agencyId,
            status,
            false);
    session.setRegistrationType(type);
    session.setIsConsultantDirectlySet(false);
    session.setLanguageCode(LanguageCode.de);
    var saved = sessionRepository.save(session);
    entityManager.flush();
    if (consultantId != null) {
      jdbc.update("UPDATE session SET consultant_id = ? WHERE id = ?", consultantId, saved.getId());
    }
    return saved.getId();
  }

  private ResultActions open(Long sessionId) throws Exception {
    return mockMvc.perform(
        put("/users/consultants/sessions/{id}/enquiry/opened", sessionId)
            .cookie(new Cookie("CSRF-TOKEN", "test"))
            .header("X-CSRF-Token", "test"));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void marksTheEnquiryForTheCallerOnlyAndStaysUnopenedForAColleague() throws Exception {
    callerIs(COUNSELLOR_A);
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);

    open(sessionId).andExpect(status().isNoContent());

    assertThat(openedRepository.existsByConsultantIdAndSessionId(COUNSELLOR_A, sessionId)).isTrue();
    assertThat(openedRepository.existsByConsultantIdAndSessionId(COUNSELLOR_B, sessionId))
        .isFalse();
    assertThat(openedRepository.findOpenedSessionIds(COUNSELLOR_B, List.of(sessionId))).isEmpty();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void openingTwiceIsFineAndLeavesOneRow() throws Exception {
    callerIs(COUNSELLOR_A);
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);

    open(sessionId).andExpect(status().isNoContent());
    open(sessionId).andExpect(status().isNoContent());

    assertThat(openedRepository.findOpenedSessionIds(COUNSELLOR_A, List.of(sessionId)))
        .containsExactly(sessionId);
    assertThat(openedRepository.countByConsultantId(COUNSELLOR_A)).isEqualTo(1);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void anonymousEnquiryBehavesLikeARegisteredOne() throws Exception {
    callerIs(COUNSELLOR_A);
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.ANONYMOUS, AGENCY, null);

    open(sessionId).andExpect(status().isNoContent());

    assertThat(openedRepository.existsByConsultantIdAndSessionId(COUNSELLOR_A, sessionId)).isTrue();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void counsellorOfAnotherAgencyIsRefused() throws Exception {
    callerIs(COUNSELLOR_OTHER_AGENCY);
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);

    open(sessionId).andExpect(status().isForbidden());

    assertThat(openedRepository.count()).isZero();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void askerIsRefused() throws Exception {
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);

    open(sessionId).andExpect(status().isForbidden());

    assertThat(openedRepository.count()).isZero();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void sessionThatIsNoWaitingEnquiryIsAConflict() throws Exception {
    callerIs(COUNSELLOR_A);
    var inProgress =
        givenSession(SessionStatus.IN_PROGRESS, RegistrationType.REGISTERED, AGENCY, COUNSELLOR_B);

    open(inProgress).andExpect(status().isConflict());

    assertThat(openedRepository.count()).isZero();
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void unknownSessionIsNotFound() throws Exception {
    callerIs(COUNSELLOR_A);

    open(987654321L).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void marksVanishWhenTheSessionOrTheCounsellorIsDeleted() throws Exception {
    callerIs(COUNSELLOR_A);
    var sessionId = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);
    open(sessionId).andExpect(status().isNoContent());
    assertThat(openedRepository.count()).isEqualTo(1);

    // The delete workflows remove sessions and consultants with plain deletes; the foreign keys
    // (ON DELETE CASCADE) carry the marks away.
    jdbc.update("DELETE FROM session WHERE id = ?", sessionId);
    assertThat(openedRepository.count()).isZero();

    var second = givenSession(SessionStatus.NEW, RegistrationType.REGISTERED, AGENCY, null);
    open(second).andExpect(status().isNoContent());
    openedRepository.save(
        new de.caritas.cob.userservice.api.model.ConsultantEnquiryOpened(
            COUNSELLOR_OTHER_AGENCY, second, java.time.LocalDateTime.now()));
    openedRepository.flush();
    jdbc.update("DELETE FROM consultant_agency WHERE consultant_id = ?", COUNSELLOR_OTHER_AGENCY);
    jdbc.update(
        "UPDATE session SET consultant_id = NULL WHERE consultant_id = ?", COUNSELLOR_OTHER_AGENCY);
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", COUNSELLOR_OTHER_AGENCY);
    assertThat(openedRepository.countByConsultantId(COUNSELLOR_OTHER_AGENCY)).isZero();
  }
}
