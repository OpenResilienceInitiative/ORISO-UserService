package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Marking an advice seeker for deletion closes their enquiries nobody has taken yet, so they leave
 * the counsellors' enquiry lists and stop blocking the deletion of an agency's last counsellor
 * (ORISO-Admin#1521).
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
class SessionRepositoryCloseEnquiriesIT {

  private static final long TENANT = 990_101L;
  private static final long AGENCY = 990_601L;

  @Autowired private SessionRepository sessionRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void closeUnassignedEnquiries_Should_closeOnlyTheAskersUntakenEnquiries() {
    var asker = asker();
    var other = asker();
    var counsellor = consultantRepository.findAll().iterator().next();
    var initial = session(asker, SessionStatus.INITIAL, null);
    var fresh = session(asker, SessionStatus.NEW, null);
    var assigned = session(asker, SessionStatus.NEW, counsellor);
    var running = session(asker, SessionStatus.IN_PROGRESS, counsellor);
    var othersEnquiry = session(other, SessionStatus.NEW, null);
    entityManager.flush();
    var now = LocalDateTime.of(2026, 11, 13, 15, 0);

    int closed = sessionRepository.closeUnassignedEnquiries(asker, now);
    entityManager.clear();

    assertThat(closed).isEqualTo(2);
    assertThat(status(initial)).isEqualTo(SessionStatus.DONE);
    assertThat(status(fresh)).isEqualTo(SessionStatus.DONE);
    assertThat(status(assigned)).isEqualTo(SessionStatus.NEW);
    assertThat(status(running)).isEqualTo(SessionStatus.IN_PROGRESS);
    assertThat(status(othersEnquiry)).isEqualTo(SessionStatus.NEW);
    assertThat(sessionRepository.findById(fresh.getId()).orElseThrow().getUpdateDate())
        .isEqualTo(now);
    assertThat(
            sessionRepository.findByAgencyIdAndStatusAndConsultantIsNull(AGENCY, SessionStatus.NEW))
        .extracting(Session::getId)
        .containsExactly(othersEnquiry.getId());
  }

  private SessionStatus status(Session session) {
    return sessionRepository.findById(session.getId()).orElseThrow().getStatus();
  }

  private User asker() {
    var user = new User();
    user.setUserId(UUID.randomUUID().toString());
    user.setUsername("enc." + user.getUserId());
    user.setEmail(user.getUserId() + "@example.org");
    user.setTenantId(TENANT);
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    entityManager.persist(user);
    return user;
  }

  private Session session(User user, SessionStatus status, Consultant consultant) {
    var session = new Session();
    session.setUser(user);
    session.setConsultant(consultant);
    session.setPostcode("12345");
    session.setAgencyId(AGENCY);
    session.setStatus(status);
    session.setTenantId(TENANT);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    entityManager.persist(session);
    return session;
  }
}
