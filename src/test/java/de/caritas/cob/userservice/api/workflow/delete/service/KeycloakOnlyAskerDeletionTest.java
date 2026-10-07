package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.httpheader.*;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
class KeycloakOnlyAskerDeletionTest {
  @Autowired UserRepository users;
  @Autowired DraftMessageRepository drafts;
  @Autowired EventNotificationRepository notifications;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Test
  void missingLocalAccountStillCleansRemoteAndUnlinkedContentAndRetriesFailures() throws Exception {
    String id = "keycloak-only-asker";
    assertThat(users.findById(id)).isEmpty();
    var now = LocalDateTime.now();
    drafts.save(
        DraftMessage.builder()
            .userId(id)
            .scopeKey("remaining")
            .text("private")
            .createDate(now)
            .updateDate(now)
            .build());
    notifications.save(
        EventNotification.builder()
            .recipientUserId(id)
            .eventType("test")
            .category("test")
            .title("private")
            .createDate(now)
            .build());
    entityManager.flush();
    var failAppointment = new AtomicBoolean(true);
    var identityPresent = new AtomicBoolean(true);
    var appointmentCalls = new AtomicInteger();
    var identityCalls = new AtomicInteger();
    var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remote.createContext(
        "/",
        exchange -> {
          int status;
          if (exchange.getRequestURI().getPath().startsWith("/identity/")) {
            identityCalls.incrementAndGet();
            status = identityPresent.getAndSet(false) ? 204 : 404;
          } else {
            appointmentCalls.incrementAndGet();
            status = failAppointment.get() ? 503 : 204;
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    remote.start();
    try (var context = new StaticApplicationContext()) {
      String url = "http://127.0.0.1:" + remote.getAddress().getPort();
      var transport = new RestTemplate();
      var factory = new AppointmentAskerServiceApiControllerFactory();
      ReflectionTestUtils.setField(factory, "appointmentServiceApiUrl", url);
      ReflectionTestUtils.setField(factory, "restTemplate", transport);
      var config = new IdentityConfig();
      var tech = new TechnicalUserConfig();
      tech.setUsername("test");
      tech.setPassword("test");
      config.setTechnicalUser(tech);
      var security = new SecurityHeaderSupplier(new AuthenticatedUser());
      ReflectionTestUtils.setField(security, "csrfHeaderProperty", "X-CSRF");
      ReflectionTestUtils.setField(security, "csrfCookieProperty", "CSRF");
      var authentication =
          new IdentityAuthentication() {
            public IdentityLogin login(String u, String p) {
              return new IdentityLogin("external-test-token", 60, 60, "unused");
            }

            public boolean logout(String token) {
              throw new UnsupportedOperationException();
            }

            public boolean verifyPasswordIgnoringSecondFactor(String u, String p) {
              throw new UnsupportedOperationException();
            }
          };
      var appointments =
          new AppointmentService(
              new AppointmentConsultantServiceApiControllerFactory(),
              new AppointmentAgencyServiceApiControllerFactory(),
              factory,
              security,
              new TenantHeaderSupplier(new HttpHeadersResolver()),
              authentication,
              config);
      ReflectionTestUtils.setField(appointments, "appointmentFeatureEnabled", true);
      var remover =
          new IdentityAccountRemover() {
            public void deleteUser(String subject) {
              transport.delete(url + "/identity/" + subject);
            }

            public void rollbackUser(String subject) {
              throw new UnsupportedOperationException();
            }
          };
      context
          .getBeanFactory()
          .registerSingleton("appointment", new DeleteAppointmentServiceAskerAction(appointments));
      context
          .getBeanFactory()
          .registerSingleton("drafts", new DeleteAskerDraftMessagesAction(drafts));
      context
          .getBeanFactory()
          .registerSingleton(
              "notifications", new DeleteAskerEventNotificationsAction(notifications));
      context
          .getBeanFactory()
          .registerSingleton("identity", new DeleteKeycloakAskerAction(remover));
      var service = new InactiveAskerDeletionService(users, new ActionsRegistry(context));
      assertThat(service.delete(id))
          .singleElement()
          .satisfies(
              error ->
                  assertThat(error.getDeletionTargetType())
                      .isEqualTo(DeletionTargetType.APPOINTMENT_SERVICE));
      assertThat(identityCalls).hasValue(0);
      assertThat(drafts.findAll().stream().anyMatch(d -> id.equals(d.getUserId()))).isTrue();
      failAppointment.set(false);
      assertThat(service.delete(id)).isEmpty();
      assertThat(identityPresent).isFalse();
      assertThat(drafts.findAll().stream().anyMatch(d -> id.equals(d.getUserId()))).isFalse();
      assertThat(notifications.findAll().stream().anyMatch(n -> id.equals(n.getRecipientUserId())))
          .isFalse();
      assertThat(service.delete(id)).isEmpty();
      assertThat(appointmentCalls).hasValue(3);
      assertThat(identityCalls).hasValue(2);
      assertThat(users.findById(id)).isEmpty();
    } finally {
      remote.stop(0);
    }
  }
}
