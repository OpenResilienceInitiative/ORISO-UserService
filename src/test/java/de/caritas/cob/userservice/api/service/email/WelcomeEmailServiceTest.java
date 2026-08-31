package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WelcomeEmailServiceTest {

  @Mock private SystemNotificationEmailSettingsService emailSettingsService;
  @Mock private OrisoEmailDispatcher dispatcher;

  // Real, so the test asserts that a mail comes out rather than that a method
  // was called.
  @Spy private OrisoEmailRenderer emailRenderer = new OrisoEmailRenderer();
  @Spy private OrisoEmailBrand emailBrand = new OrisoEmailBrand();

  @InjectMocks private WelcomeEmailService service;

  private final SupervisorAddedEmailSettings smtp =
      new SupervisorAddedEmailSettings(
          "smtp.example.org", 587, false, "user", "secret", "no-reply@example.org", "#a5000a");

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@dummy.invalid");
    ReflectionTestUtils.setField(emailBrand, "platformName", "Online-Beratung");
    ReflectionTestUtils.setField(emailBrand, "orgName", "ORISO");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtp));
  }

  private static User user(String email) {
    var user = new User();
    user.setEmail(email);
    user.setTenantId(1L);
    return user;
  }

  @Test
  void sendsTheGeneratedUserNameToTheAddressGiven() {
    service.sendWelcomeEmail(user("jemand@example.org"), "ruhiges-yak-1428");

    var email = ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(dispatcher).send(eq(smtp), eq("jemand@example.org"), email.capture());

    // The user name is the whole point: ORISO cannot recover it, so a mail that
    // does not carry it is worse than no mail.
    assertThat(email.getValue().html()).contains("ruhiges-yak-1428");
    assertThat(email.getValue().text()).contains("ruhiges-yak-1428");
    assertThat(email.getValue().subject()).isEqualTo("Willkommen bei Online-Beratung");
  }

  @Test
  void staysSilentWhenAnAnonymousAccountHasNoRealAddress() {
    // The normal case for an anonymous registration, not an error: the user
    // name is shown on screen at the end of registration either way.
    // `User.email` is non-null by contract — an anonymous account carries a
    // synthetic address ending in the configured dummy suffix rather than no
    // address at all, so that is the case worth guarding.
    service.sendWelcomeEmail(user("anon-1234@dummy.invalid"), "ruhiges-yak-1428");
    service.sendWelcomeEmail(user(""), "ruhiges-yak-1428");

    verify(dispatcher, never()).send(any(), anyString(), any());
  }

  @Test
  void staysSilentWithoutAUserNameToCarry() {
    // A mail saying "keep this safe" and then showing nothing is worse than
    // no mail at all.
    service.sendWelcomeEmail(user("jemand@example.org"), null);
    service.sendWelcomeEmail(user("jemand@example.org"), "  ");

    verify(dispatcher, never()).send(any(), anyString(), any());
  }

  @Test
  void staysSilentWhenTheTenantHasNoSmtpSettings() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    service.sendWelcomeEmail(user("jemand@example.org"), "ruhiges-yak-1428");

    verify(dispatcher, never()).send(any(), anyString(), any());
  }

  @Test
  void doesNotCallSmtpForAUserWithoutATenant() {
    var user = user("jemand@example.org");
    user.setTenantId(null);

    service.sendWelcomeEmail(user, "ruhiges-yak-1428");

    verify(dispatcher, never()).send(any(), anyString(), any());
  }

  @Test
  void survivesANullUser() {
    service.sendWelcomeEmail(null, "ruhiges-yak-1428");

    verify(dispatcher, never()).send(any(), anyString(), any());
  }
}
