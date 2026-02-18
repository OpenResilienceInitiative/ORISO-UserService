package de.caritas.cob.userservice.api.service.emailsupplier;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.keycloak.common.util.CollectionUtil.isEmpty;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.transaction.Transactional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/** Supplier to provide mails to be sent when a new enquiry was created. */
@RequiredArgsConstructor
@Service
@Slf4j
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class NewEnquiryEmailSupplier implements EmailSupplier {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AgencyService agencyService;

  private final @NonNull ReleaseToggleService releaseToggleService;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  private Session session;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  private final TenantTemplateSupplier tenantTemplateSupplier;

  public void setCurrentSession(Session session) {
    this.session = session;
  }

  /**
   * Generates the enquiry notification mails sent to regarding consultants when a new enquiry has
   * been created.
   *
   * @return a list of the generated {@link MailDTO}
   */
  @Override
  @Transactional
  public List<MailDTO> generateEmails() {
    log.info("Generating emails for new enquiry");
    List<ConsultantAgency> consultantAgencyList =
        consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(session.getAgencyId());
    log.info("Retrieved consultant agency list {}", consultantAgencyList);
    if (isEmpty(consultantAgencyList)) {
      return emptyList();
    }
    AgencyDTO agency = agencyService.getAgency(session.getAgencyId());
    log.info("Retrieved agency {}", agency);
    return consultantAgencyList.stream()
        .filter(this::validConsultantAgency)
        .filter(this::shouldSendNewEnquiryNotificationForConsultant)
        .map(toEnquiryMailDTO(agency))
        .collect(Collectors.toList());
  }

  private boolean validConsultantAgency(ConsultantAgency consultantAgency) {
    if (consultantAgency == null) {
      return false;
    }
    var consultant = consultantAgency.getConsultant();
    var isValid =
        nonNull(consultant) && isNotBlank(consultant.getEmail()) && !consultant.isAbsent();
    logReasonsIfConsultantNotValid(consultantAgency, consultant, isValid);
    return isValid;
  }

  private static void logReasonsIfConsultantNotValid(
      ConsultantAgency consultantAgency, Consultant consultant, boolean isValid) {
    if (!isValid) {
      if (consultant == null) {
        log.debug(
            "Cannot send email notification: consultant is null for agency {}",
            consultantAgency.getId());
      } else if (!isNotBlank(consultant.getEmail())) {
        log.debug(
            "Cannot send email notification: email is blank for consultant {}", consultant.getId());
      } else if (consultant.isAbsent()) {
        log.debug(
            "Skipping new enquiry email notification: consultant {} is marked as absent",
            consultant.getId());
      }
    }
  }

  private boolean shouldSendNewEnquiryNotificationForConsultant(ConsultantAgency consultantAgency) {
    if (!releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) {
      // since the notificationtoggle is off, by default send notifications
      return true;
    }

    var consultant = consultantAgency.getConsultant();
    var shouldSend = wantsToReceiveNotificationsAboutNewEnquiry(consultant);
    if (shouldSend) {
      log.debug(
          "All notification checks passed for consultant {} - will generate email",
          consultant.getId());
    }
    return shouldSend;
  }

  private boolean wantsToReceiveNotificationsAboutNewEnquiry(Consultant consultant) {
    NotificationsSettingsDTO notificationsSettingsDTO =
        deserializeNotificationSettingsDTOOrDefaultIfNull(consultant);
    var notificationEnabled = consultant.isNotificationsEnabled();
    if (!notificationEnabled) {
      log.debug(
          "Skipping email notification: notifications are disabled for consultant {}",
          consultant.getId());
    }
    var initialEnquiryNotificationsEnabled =
        notificationsSettingsDTO.getInitialEnquiryNotificationEnabled();
    if (!initialEnquiryNotificationsEnabled) {
      log.debug(
          "Skipping email notification: initial enquiry notification setting is disabled for consultant {}",
          consultant.getId());
    }
    return notificationEnabled && initialEnquiryNotificationsEnabled;
  }

  private Function<ConsultantAgency, MailDTO> toEnquiryMailDTO(AgencyDTO agency) {
    return consultantAgency ->
        mailOf(consultantAgency.getConsultant(), session.getPostcode(), agency.getName());
  }

  private MailDTO mailOf(Consultant consultant, String postCode, String agency) {

    var templateAttributes = new ArrayList<TemplateDataDTO>();
    templateAttributes.add(new TemplateDataDTO().key("name").value(consultant.getFullName()));
    templateAttributes.add(new TemplateDataDTO().key("plz").value(postCode));
    templateAttributes.add(new TemplateDataDTO().key("beratungsstelle").value(agency));

    if (!multiTenancyEnabled) {
      templateAttributes.add(new TemplateDataDTO().key("url").value(applicationBaseUrl));
    } else {
      templateAttributes.addAll(tenantTemplateSupplier.getTemplateAttributes());
    }

    var language =
        de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode.fromValue(
            consultant.getLanguageCode().toString());

    return new MailDTO()
        .template(TEMPLATE_NEW_ENQUIRY_NOTIFICATION)
        .email(consultant.getEmail())
        .language(language)
        .dialect(consultant.getDialect())
        .templateData(templateAttributes);
  }
}
