package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.httpheader.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
class RegisteredOnlyInactivityGuardTest {
  @Autowired UserRepository users;
  @Autowired ConsultantRepository consultants;
  @Autowired SessionRepository sessions;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired javax.sql.DataSource dataSource;

  @Test
  void bothLegacyModesLeaveEveryEnrolledIdentityToItsLifecycleOwner() {
    var user = new User("guarded-registered", null, "guarded", "guarded@example.invalid", false);
    user.setCreateDate(LocalDateTime.now().minusYears(3));
    user = users.save(user);
    var session = new Session(user, 0, "12345", null, Session.SessionStatus.INITIAL, false);
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    session.setIsConsultantDirectlySet(false);
    session.setCreateDate(LocalDateTime.now().minusYears(3));
    sessions.save(session);
    entityManager.flush();
    assertThat(
            users.findAllByDeleteDateNullAndNoRunningSessionsAndCreateDateOlderThan(
                LocalDateTime.now().minusDays(30)))
        .extracting(User::getUserId)
        .contains(user.getUserId());
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,status"
            + " VARCHAR(20))");
    jdbc.update("INSERT INTO account_inactivity VALUES (?,'ACTIVE')", user.getUserId());
    // The shared JPA seed also contains legacy candidates; enroll them as rollout does.
    jdbc.update(
        "INSERT INTO account_inactivity SELECT user_id,'ACTIVE' FROM user WHERE user_id<>?",
        user.getUserId());
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    var auth =
        new IdentityAuthentication() {
          public IdentityLogin login(String u, String p) {
            throw new UnsupportedOperationException();
          }

          public boolean logout(String t) {
            throw new UnsupportedOperationException();
          }

          public boolean verifyPasswordIgnoringSecondFactor(String u, String p) {
            throw new UnsupportedOperationException();
          }
        };
    var templates =
        new TenantTemplateSupplier(
            new TenantService(
                new TenantServiceApiControllerFactory(), new ConcurrentMapCacheManager()),
            new ApplicationSettingsService(
                new ApplicationSettingsApiControllerFactory(),
                headers,
                new TenantHeaderSupplier(new HttpHeadersResolver()),
                new IdentityConfig(),
                auth));
    var mail =
        new WorkflowErrorMailService(
            new MailService(headers, new MailServiceApiControllerFactory()), templates);
    try (var context = new StaticApplicationContext()) {
      // Real registry is intentionally empty: crossing into the legacy deletion workflow fails
      // before external effects. Enrolled accounts must never enter that pipeline in either mode.
      var deletion =
          new DeleteUserAccountService(
              users,
              consultants,
              new ActionsRegistry(context),
              mail,
              new DeletionLifecycleService());
      var legacy = new DeleteUsersRegisteredOnlyService(users, deletion, mail, jdbc);
      ReflectionTestUtils.setField(legacy, "userRegisteredOnlyDeleteWorkflowCheckDays", 30);
      for (String state :
          java.util.List.of(
              "ACTIVE", "SUSPENDING", "SUSPENDED", "DELETING", "DELETED", "REACTIVATING")) {
        jdbc.update(
            "UPDATE account_inactivity SET status=? WHERE identity_id=?", state, user.getUserId());
        assertThatCode(legacy::deleteUserAccountsTimeSensitive).doesNotThrowAnyException();
        assertThatCode(legacy::deleteUserAccountsTimeInsensitive).doesNotThrowAnyException();
        assertThat(users.findById(user.getUserId())).isPresent();
      }
      jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", user.getUserId());
      assertThatThrownBy(legacy::deleteUserAccountsTimeSensitive)
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("DeleteKeycloakAskerAction");
    }
  }
}
