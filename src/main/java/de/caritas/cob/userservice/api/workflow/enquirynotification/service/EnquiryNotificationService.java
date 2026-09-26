package de.caritas.cob.userservice.api.workflow.enquirynotification.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsOrDefaultIfNull;
import static de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier.TEMPLATE_DAILY_ENQUIRY_NOTIFICATION;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.workflow.enquirynotification.model.EnquiriesNotificationMailContent;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service to build and send email notifications for open enquiries. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnquiryNotificationService {

  private static final String MAIL_SUBJECT = "Online-Beratung | Unbeantwortete Erstanfragen";
  private static final String TASK_NAME = "enquiry-notification";
  private static final DateTimeFormatter UTC_DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'");

  private final @NonNull MailService mailService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantAgencyService consultantAgencyService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull TenantService tenantService;
  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;

  private final @NonNull ReleaseToggleService releaseToggleService;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${enquiry.open.notification.check.hours}")
  private Long openEnquiryCheckHours;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${enquiry.open.notification.claim.duration:PT30M}")
  private Duration claimDuration;

  /** Entry method to build and send email notifications. */
  public void sendEmailNotificationsForOpenEnquiries() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    var generatedAt = nowInUtc();
    var agencyIdsWithOpenEnquiries = findAgencyIdsWithOpenEnquiries(generatedAt);
    var agenciesWithOpenEnquiries =
        agencyService.getAgencies(new ArrayList<>(agencyIdsWithOpenEnquiries.keySet()));
    var agencyIdToAgency =
        agenciesWithOpenEnquiries.stream()
            .collect(Collectors.toMap(AgencyDTO::getId, Function.identity()));
    var mailsContentForAgencies =
        createMailsContentForAgencies(agencyIdsWithOpenEnquiries, agencyIdToAgency, generatedAt);

    mailsContentForAgencies.forEach(this::buildAndSendEnquiryNotificationMails);
  }

  private Map<Long, List<Session>> findAgencyIdsWithOpenEnquiries(LocalDateTime generatedAt) {
    return sessionRepository.findByStatus(SessionStatus.NEW).stream()
        .filter(session -> longerOpenThanCheckHours(session, generatedAt))
        .collect(Collectors.groupingBy(Session::getAgencyId));
  }

  private boolean longerOpenThanCheckHours(Session session, LocalDateTime generatedAt) {
    var enquiryMessageDate = session.getEnquiryMessageDate();

    if (nonNull(enquiryMessageDate)) {
      return generatedAt.minusHours(openEnquiryCheckHours).isAfter(enquiryMessageDate);
    }
    return false;
  }

  private Collection<EnquiriesNotificationMailContent> createMailsContentForAgencies(
      Map<Long, List<Session>> agencyIdsWithOpenEnquiries,
      Map<Long, AgencyDTO> agencyIdToAgency,
      LocalDateTime generatedAt) {
    return agencyIdsWithOpenEnquiries.entrySet().stream()
        .map(toMailContent(agencyIdToAgency, generatedAt))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private Function<Entry<Long, List<Session>>, EnquiriesNotificationMailContent> toMailContent(
      Map<Long, AgencyDTO> agencyIdToAgency, LocalDateTime generatedAt) {
    return agencyIdEnquiriesEntry -> {
      var agencyId = agencyIdEnquiriesEntry.getKey();
      AgencyDTO agency = agencyIdToAgency.get(agencyId);
      if (agency == null || agency.getName() == null || agency.getName().isBlank()) {
        log.warn("Skipping digest for agency {}: agency data is unavailable", agencyId);
        return null;
      }
      var agencyName = agency.getName();
      Long tenantId = agency.getTenantId();
      var matchingEnquiries =
          agencyIdEnquiriesEntry.getValue().stream()
              .filter(session -> sameTenant(tenantId, session.getTenantId()))
              .toList();
      if (matchingEnquiries.isEmpty()) {
        log.warn("Skipping digest for agency {}: no enquiry has the agency tenant", agencyId);
        return null;
      }
      String baseUrl;
      try {
        baseUrl = resolveBaseUrl(tenantId);
      } catch (RuntimeException exception) {
        log.error("Skipping digest for agency {}: tenant URL is unavailable", agencyId, exception);
        return null;
      }
      return EnquiriesNotificationMailContent.builder()
          .agencyId(agencyId)
          .amountOfOpenEnquiries((long) matchingEnquiries.size())
          .agencyName(agencyName)
          .tenantId(tenantId)
          .baseUrl(baseUrl)
          .oldestEnquiryDate(
              matchingEnquiries.stream()
                  .map(Session::getEnquiryMessageDate)
                  .min(Comparator.naturalOrder())
                  .orElseThrow())
          .generatedAt(generatedAt)
          .build();
    };
  }

  private boolean sameTenant(Long agencyTenantId, Long enquiryTenantId) {
    return !multitenancyEnabled || Objects.equals(agencyTenantId, enquiryTenantId);
  }

  private String resolveBaseUrl(Long tenantId) {
    if (!multitenancyEnabled) {
      return validatedBaseUrl(applicationBaseUrl);
    }
    if (tenantId == null) {
      throw new IllegalStateException("Digest tenant id is missing");
    }
    var tenant = tenantService.getRestrictedTenantData(tenantId);
    if (tenant == null
        || tenant.getSubdomain() == null
        || !tenant.getSubdomain().matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
      throw new IllegalStateException("Digest tenant subdomain is missing");
    }
    return validatedBaseUrl(tenantTemplateSupplier.getTenantBaseUrl(tenant));
  }

  private static String validatedBaseUrl(String value) {
    if (value == null) {
      throw new IllegalStateException("Digest base URL is missing");
    }
    URI uri = URI.create(value);
    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException("Digest base URL is invalid");
    }
    return value;
  }

  private void buildAndSendEnquiryNotificationMails(
      EnquiriesNotificationMailContent enquiryMailContent) {
    var mailDTOs =
        consultantAgencyService.findConsultantsByAgencyId(enquiryMailContent.getAgencyId()).stream()
            .map(ConsultantAgency::getConsultant)
            .filter(c -> wantsToReceiveNotifications(c))
            .filter(c -> sameTenant(enquiryMailContent.getTenantId(), c.getTenantId()))
            .map(consultant -> buildMailTO(consultant, enquiryMailContent))
            .collect(Collectors.toList());

    buildAndSendNotificationEmail(mailDTOs);
  }

  private boolean wantsToReceiveNotifications(Consultant consultant) {
    if (releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) {
      return consultant.isNotificationsEnabled()
          && deserializeNotificationSettingsOrDefaultIfNull(consultant)
              .isInitialEnquiryNotificationEnabled();
    } else {
      return consultant.getNotifyEnquiriesRepeating();
    }
  }

  private MailDTO buildMailTO(
      Consultant consultant, EnquiriesNotificationMailContent enquiryNotificationContent) {
    var attributes = new ArrayList<TemplateDataDTO>();
    attributes.add(new TemplateDataDTO().key("subject").value(MAIL_SUBJECT));
    attributes.add(new TemplateDataDTO().key("consultant_name").value(consultant.getFullName()));
    attributes.add(new TemplateDataDTO().key("url").value(enquiryNotificationContent.getBaseUrl()));
    attributes.add(
        new TemplateDataDTO().key("agency_name").value(enquiryNotificationContent.getAgencyName()));
    attributes.add(
        new TemplateDataDTO()
            .key("enquiries")
            .value(String.valueOf(enquiryNotificationContent.getAmountOfOpenEnquiries())));
    attributes.add(
        new TemplateDataDTO()
            .key("oldestRequestAge")
            .value(
                Duration.between(
                            enquiryNotificationContent.getOldestEnquiryDate(),
                            enquiryNotificationContent.getGeneratedAt())
                        .toHours()
                    + " h"));
    attributes.add(
        new TemplateDataDTO()
            .key("digestGeneratedAt")
            .value(UTC_DATE_TIME.format(enquiryNotificationContent.getGeneratedAt())));
    if (enquiryNotificationContent.getTenantId() != null) {
      attributes.add(
          new TemplateDataDTO()
              .key("tenantId")
              .value(enquiryNotificationContent.getTenantId().toString()));
    }
    if (consultant.getTenantId() != null) {
      attributes.add(
          new TemplateDataDTO()
              .key("recipientTenantId")
              .value(consultant.getTenantId().toString()));
    }
    return new MailDTO()
        .template(TEMPLATE_DAILY_ENQUIRY_NOTIFICATION)
        .email(consultant.getEmail())
        .language(languageOf(consultant.getLanguageCode()))
        .dialect(consultant.getDialect())
        .templateData(attributes);
  }

  private void buildAndSendNotificationEmail(List<MailDTO> mailsToSend) {
    if (isNotEmpty(mailsToSend)) {
      var mailsDTO = new MailsDTO().mails(mailsToSend);
      mailService.sendEmailNotification(mailsDTO);
    }
  }

  private static de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode languageOf(
      LanguageCode languageCode) {
    return de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode.fromValue(
        languageCode.toString());
  }
}
