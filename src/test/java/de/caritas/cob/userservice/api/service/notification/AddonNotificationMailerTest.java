package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.notification.AddonNotificationService.RecipientKind;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddonNotificationMailerTest {

  private static final String ASKER_ID = "asker";
  private static final String CONSULTANT_ID = "consultant";
  private static final String NEW_MESSAGE_ON = "{\"newChatMessageNotificationEnabled\":true}";
  private static final String NEW_MESSAGE_OFF = "{\"newChatMessageNotificationEnabled\":false}";

  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private NotificationAccountEligibility accountEligibility;
  @Mock private DoNotDisturbService doNotDisturb;
  @Mock private TenantService tenants;
  @Mock private TenantTemplateSupplier tenantTemplates;
  @Mock private MailService mailService;
  @InjectMocks private AddonNotificationMailer mailer;

  private User asker;
  private Consultant consultant;
  private Settings tenantSettings;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(mailer, "multitenancyEnabled", true);
    ReflectionTestUtils.setField(mailer, "emailDummySuffix", "@dummy.local");

    asker = new User();
    asker.setUserId(ASKER_ID);
    asker.setEmail("asker@example.org");
    asker.setNotificationsEnabled(true);
    asker.setNotificationsSettings(NEW_MESSAGE_ON);
    asker.setLanguageFormal(false);
    when(userRepository.findByUserIdAndDeleteDateIsNull(ASKER_ID)).thenReturn(Optional.of(asker));

    consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setEmail("consultant@example.org");
    consultant.setNotificationsEnabled(true);
    consultant.setNotificationsSettings(NEW_MESSAGE_ON);
    consultant.setLanguageFormal(true);
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    when(accountEligibility.allows(anyString())).thenReturn(true);
    when(accountEligibility.allows(any(Consultant.class))).thenReturn(true);
    tenantSettings = new Settings();
    when(tenants.getRestrictedTenantDataFresh(3L))
        .thenReturn(new RestrictedTenantDTO().id(3L).subdomain("traeger").settings(tenantSettings));
    when(tenantTemplates.getTenantBaseUrl(any())).thenReturn("https://traeger.example.org");
  }

  @Test
  void askerWhoWantsNewMessageMailGetsTheNeutralMail() {
    var mail = mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L).orElseThrow();

    assertThat(mail.getTemplate()).isEqualTo(EmailSupplier.TEMPLATE_ADDON_NOTIFICATION);
    assertThat(mail.getEmail()).isEqualTo("asker@example.org");
    assertThat(mail.getLanguage()).isEqualTo(LanguageCode.DE);
    assertThat(mail.getDialect()).isEqualTo(Dialect.INFORMAL);
    assertThat(attributes(mail))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "tenantId", "3",
                "recipientTenantId", "3",
                "url", "https://traeger.example.org"));
  }

  @Test
  void askerWithNotificationsOffGetsNoMail() {
    asker.setNotificationsEnabled(false);

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void askerWithNewMessageSwitchOffGetsNoMail() {
    asker.setNotificationsSettings(NEW_MESSAGE_OFF);

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void askerWithoutStoredSettingsGetsNoMail() {
    asker.setNotificationsSettings(null);

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void askerWithDummyAddressGetsNoMail() {
    asker.setEmail("anon@dummy.local");

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void askerMailFollowsTheTenantsAskerMailSwitch() {
    tenantSettings.featureAskerEmailEnabled(false);

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void inactiveAskerAccountGetsNoMail() {
    when(accountEligibility.allows(ASKER_ID)).thenReturn(false);

    assertThat(mailer.mailFor(RecipientKind.ASKER, ASKER_ID, 3L)).isEmpty();
  }

  @Test
  void consultantWhoWantsNewMessageMailGetsTheNeutralMail() {
    var mail = mailer.mailFor(RecipientKind.CONSULTANT, CONSULTANT_ID, 3L).orElseThrow();

    assertThat(mail.getEmail()).isEqualTo("consultant@example.org");
    assertThat(mail.getDialect()).isEqualTo(Dialect.FORMAL);
  }

  @Test
  void consultantNewMessageToggleOffMeansNoMail() {
    consultant.setNotifyNewChatMessageFromAdviceSeeker(false);

    assertThat(mailer.mailFor(RecipientKind.CONSULTANT, CONSULTANT_ID, 3L)).isEmpty();
  }

  @Test
  void consultantNewMessageSettingOffMeansNoMail() {
    consultant.setNotificationsSettings(NEW_MESSAGE_OFF);

    assertThat(mailer.mailFor(RecipientKind.CONSULTANT, CONSULTANT_ID, 3L)).isEmpty();
  }

  @Test
  void consultantInDoNotDisturbGetsNoMail() {
    when(doNotDisturb.isInDoNotDisturb(CONSULTANT_ID)).thenReturn(true);

    assertThat(mailer.mailFor(RecipientKind.CONSULTANT, CONSULTANT_ID, 3L)).isEmpty();
  }

  @Test
  void sendIfWantedSendsOnlyAWantedMail() {
    mailer.sendIfWanted(RecipientKind.ASKER, ASKER_ID, 3L);
    verify(mailService).sendEmailNotification(any());

    asker.setNotificationsEnabled(false);
    org.mockito.Mockito.clearInvocations(mailService);
    mailer.sendIfWanted(RecipientKind.ASKER, ASKER_ID, 3L);
    verify(mailService, never()).sendEmailNotification(any());
  }

  @Test
  void failuresStayInsideTheMailer() {
    when(tenants.getRestrictedTenantDataFresh(3L)).thenThrow(new IllegalStateException("down"));

    mailer.sendIfWanted(RecipientKind.ASKER, ASKER_ID, 3L);

    verify(mailService, never()).sendEmailNotification(any());
  }

  private static Map<String, String> attributes(MailDTO mail) {
    return mail.getTemplateData().stream()
        .collect(Collectors.toMap(TemplateDataDTO::getKey, TemplateDataDTO::getValue));
  }
}
