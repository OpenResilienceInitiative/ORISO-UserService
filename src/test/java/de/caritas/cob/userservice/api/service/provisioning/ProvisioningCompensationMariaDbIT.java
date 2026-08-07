package de.caritas.cob.userservice.api.service.provisioning;

import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.DATABASE_USER;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.IDENTITY_USER;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.SESSION;
import static de.caritas.cob.userservice.api.service.provisioning.ProvisioningResource.USER_AGENCY;
import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.SessionTopic;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves that the provisioning compensation order can remove a fully persisted account graph on the
 * same MariaDB schema used by PreDev.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class ProvisioningCompensationMariaDbIT {

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private SessionDataRepository sessionDataRepository;
  @Autowired private SessionTopicRepository sessionTopicRepository;
  @Autowired private UserAgencyRepository userAgencyRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void incompleteProvisioningRemovesRelationSessionChildrenAndUserInReverseOrder() {
    User user =
        new User(
            "provisioning-compensation-user",
            null,
            "provisioning-compensation-user",
            "provisioning-compensation@example.test",
            false);
    user.setCreateDate(LocalDateTime.now());
    user.setUpdateDate(LocalDateTime.now());
    user = userRepository.save(user);
    UserAgency userAgency = new UserAgency(user, 42L);
    userAgency.setCreateDate(LocalDateTime.now());
    userAgency.setUpdateDate(LocalDateTime.now());
    userAgency = userAgencyRepository.save(userAgency);
    Session session = persistedSession(user);
    sessionDataRepository.save(
        new SessionData(session, SessionData.SessionDataType.REGISTRATION, "postcode", "12345"));
    sessionTopicRepository.save(
        SessionTopic.builder()
            .session(session)
            .topicId(7L)
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .build());
    entityManager.flush();
    entityManager.clear();

    User persistedUser = userRepository.findById(user.getUserId()).orElseThrow();
    Session persistedSession = sessionRepository.findById(session.getId()).orElseThrow();
    UserAgency persistedUserAgency =
        userAgencyRepository.findById(userAgency.getId()).orElseThrow();
    AtomicBoolean identityCompensated = new AtomicBoolean();
    ProvisioningAttempt attempt =
        new ProvisioningCompensator(new SimpleMeterRegistry())
            .begin(ProvisioningWorkflow.LEGACY_ASKER_WITH_SESSION);
    attempt.register(IDENTITY_USER, () -> identityCompensated.set(true));
    attempt.register(
        DATABASE_USER,
        () -> {
          userRepository.delete(persistedUser);
          entityManager.flush();
        });
    attempt.register(
        SESSION,
        () -> {
          sessionRepository.delete(persistedSession);
          entityManager.flush();
        });
    attempt.register(
        USER_AGENCY,
        () -> {
          userAgencyRepository.delete(persistedUserAgency);
          entityManager.flush();
        });

    CompensationResult result = attempt.compensateIfIncomplete();
    entityManager.clear();

    assertThat(result.successful()).isTrue();
    assertThat(identityCompensated).isTrue();
    assertThat(userAgencyRepository.findById(userAgency.getId())).isEmpty();
    assertThat(sessionRepository.findById(session.getId())).isEmpty();
    assertThat(sessionDataRepository.findBySessionId(session.getId())).isEmpty();
    assertThat(sessionTopicRepository.findAll())
        .noneMatch(topic -> topic.getSession().equals(session));
    assertThat(userRepository.findById(user.getUserId())).isEmpty();
  }

  private Session persistedSession(User user) {
    Session session = new Session(user, 1, "12345", 42L, Session.SessionStatus.NEW, false);
    session.setLanguageCode(LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setSupervisionOptedOut(false);
    session.setCreateDate(LocalDateTime.now());
    session.setUpdateDate(LocalDateTime.now());
    session.setSessionData(List.of());
    session.setSessionTopics(List.of());
    return sessionRepository.save(session);
  }
}
