package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.httpheader.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Actual generated HTTP transport; only the external token grant is disposable. */
final class OwnedAppointmentHttpFixture {
  static AppointmentService create(String url) {
    var factory = new AppointmentConsultantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "appointmentServiceApiUrl", url);
    ReflectionTestUtils.setField(factory, "restTemplate", new RestTemplate());
    var headers = new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-Token");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-Token");
    var authentication = mock(IdentityAuthentication.class);
    var configuration = mock(IdentityClientConfig.class);
    for (var task :
        new TaskIdentity[] {TaskIdentity.APPOINTMENT_SYNC, TaskIdentity.APPOINTMENT_CLEANUP}) {
      var credentials =
          new TaskIdentityCredentials(
              "fixture-" + task.name(), "disposable-only-secret", "subject-" + task.name());
      when(configuration.getTaskIdentity(task)).thenReturn(credentials);
      when(authentication.loginTask(credentials))
          .thenReturn(new IdentityLogin(task.name(), 60, 0, null));
    }
    var service =
        new AppointmentService(
            factory,
            mock(AppointmentAgencyServiceApiControllerFactory.class),
            mock(AppointmentAskerServiceApiControllerFactory.class),
            headers,
            mock(TenantHeaderSupplier.class),
            authentication,
            configuration);
    ReflectionTestUtils.setField(service, "appointmentFeatureEnabled", true);
    return service;
  }
}
