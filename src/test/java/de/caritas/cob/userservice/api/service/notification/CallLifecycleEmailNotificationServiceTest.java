package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CallLifecycleEmailNotificationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private DoNotDisturbService doNotDisturbService;
  @Mock private SystemNotificationEmailSettingsService emailSettingsService;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private OrisoEmailDispatcher dispatcher;

  private CallLifecycleEmailNotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new CallLifecycleEmailNotificationService(
            userRepository,
            consultantRepository,
            doNotDisturbService,
            emailSettingsService,
            emailRenderer,
            emailBrand,
            dispatcher);
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://beratung.example.org");
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@invalid.example");
  }

  @Test
  void sendsReleasedLocaleWhenAppointmentNotificationsAreEnabled() {
    User user = eligibleUser(LanguageCode.en);
    SupervisorAddedEmailSettings smtp = smtp();
    var rendered = new OrisoEmailRenderer.RenderedEmail("subject", "<html></html>", "text");
    when(userRepository.findByUserIdAndDeleteDateIsNull("user-1")).thenReturn(Optional.of(user));
    when(emailRenderer.isReleasedForSending(OrisoEmailRenderer.Tone.EN)).thenReturn(true);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(9L, null))
        .thenReturn(Optional.of(smtp));
    when(emailBrand.values("https://beratung.example.org", "#a5000a"))
        .thenReturn(Map.of("appUrl", "https://beratung.example.org"));
    when(emailRenderer.render(eq("anruf-einladung"), eq(OrisoEmailRenderer.Tone.EN), any()))
        .thenReturn(rendered);

    service.sendInvitation("user-1", 9L);

    verify(dispatcher).send(smtp, "person@example.org", rendered);
  }

  @Test
  void doesNotSendWhenRecipientDisabledAppointmentNotifications() {
    User user = eligibleUser(LanguageCode.en);
    user.setNotificationsSettings("{\"appointmentNotificationEnabled\":false}");
    when(userRepository.findByUserIdAndDeleteDateIsNull("user-1")).thenReturn(Optional.of(user));

    service.sendReminder("user-1", 9L);

    verify(dispatcher, never()).send(any(), any(), any());
  }

  @Test
  void pendingHumanReviewLocaleCannotReachDispatcher() {
    User user = eligibleUser(LanguageCode.fr);
    when(userRepository.findByUserIdAndDeleteDateIsNull("user-1")).thenReturn(Optional.of(user));
    when(emailRenderer.isReleasedForSending(OrisoEmailRenderer.Tone.FR)).thenReturn(false);

    service.sendMissed("user-1", 9L);

    verify(emailRenderer, never()).render(any(), any(), any());
    verify(dispatcher, never()).send(any(), any(), any());
  }

  @Test
  void doNotDisturbStopsLookupAndDelivery() {
    when(doNotDisturbService.isInDoNotDisturb("user-1")).thenReturn(true);

    service.sendInvitation("user-1", 9L);

    verify(userRepository, never()).findByUserIdAndDeleteDateIsNull(any());
    verify(dispatcher, never()).send(any(), any(), any());
  }

  private User eligibleUser(LanguageCode languageCode) {
    return User.builder()
        .userId("user-1")
        .username("person")
        .email("person@example.org")
        .tenantId(9L)
        .languageCode(languageCode)
        .languageFormal(true)
        .notificationsEnabled(true)
        .notificationsSettings("{\"appointmentNotificationEnabled\":true}")
        .build();
  }

  private SupervisorAddedEmailSettings smtp() {
    return new SupervisorAddedEmailSettings(
        "smtp.example.org", 587, false, "user", "password", "from@example.org", "#a5000a");
  }
}
