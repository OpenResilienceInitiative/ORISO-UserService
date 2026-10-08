package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.NotificationsAware;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.NotificationRequestFacts;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.emailsupplier.AssignEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.NewDirectEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.NewEnquiryEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.ReassignmentConfirmationEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.ReassignmentRequestEmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.notification.AskerNotificationChannelPolicy;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facade for capsuling the mail notification via the MailService */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationFacade {

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  private final @NonNull MailService mailService;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull IdentityClientConfig identityClientConfig;
  // These suppliers carry request data in fields. Resolve a prototype for every dispatch.
  private final @NonNull ObjectProvider<NewEnquiryEmailSupplier> newEnquiryEmailSupplierProvider;
  private final @NonNull ObjectProvider<NewDirectEnquiryEmailSupplier>
      newDirectEnquiryEmailSupplierProvider;
  private final @NonNull ObjectProvider<AssignEnquiryEmailSupplier>
      assignEnquiryEmailSupplierProvider;
  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;
  private final @NonNull NotificationRequestFacts notificationRequestFacts;

  private final @NonNull ReleaseToggleService releaseToggleService;
  private final @NonNull TenantService tenants;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  /**
   * Sends email notifications according to the corresponding consultant(s) when a new enquiry was
   * written.
   *
   * @param session the regarding session
   */
  @Async
  public void sendNewEnquiryEmailNotification(Session session, TenantData tenantData) {

    var sessionAlreadyAssignedToConsultant = nonNull(session.getConsultant());
    if (!sessionAlreadyAssignedToConsultant) {
      try {
        log.info(
            "Preparing to send NEW_ENQUIRY_EMAIL_NOTIFICATION email for session: {}",
            session.getId());
        TenantContext.setCurrentTenantData(tenantData);
        var newEnquiryEmailSupplier = newEnquiryEmailSupplierProvider.getObject();
        newEnquiryEmailSupplier.setCurrentSession(session);
        sendMailTasksToMailService(newEnquiryEmailSupplier, session);
      } catch (Exception ex) {
        log.error(
            "EmailNotificationFacade error: Failed to send new enquiry notification for session {}.",
            session.getId(),
            ex);
      } finally {
        TenantContext.clear();
      }
    }
  }

  @Async
  public void sendNewDirectEnquiryEmailNotification(Session session, TenantData tenantData) {
    log.info(
        "Preparing NEW_DIRECT_ENQUIRY_EMAIL_NOTIFICATION email to consultant ({}) in "
            + "agency ({})",
        session.getConsultant().getId(),
        session.getAgencyId());

    try {
      TenantContext.setCurrentTenantData(tenantData);
      var newDirectEnquiryEmailSupplier = newDirectEnquiryEmailSupplierProvider.getObject();
      newDirectEnquiryEmailSupplier.setAgencyId(session.getAgencyId());
      newDirectEnquiryEmailSupplier.setConsultantId(session.getConsultant().getId());
      newDirectEnquiryEmailSupplier.setPostCode(session.getPostcode());
      sendMailTasksToMailService(newDirectEnquiryEmailSupplier, session);
    } catch (Exception ex) {
      log.error("Failed to send NEW_DIRECT_ENQUIRY_EMAIL_NOTIFICATION", ex);
    } finally {
      TenantContext.clear();
    }
  }

  private void sendMailTasksToMailService(EmailSupplier mailsToSend) {
    sendMailTasksToMailService(mailsToSend, null);
  }

  private void sendMailTasksToMailService(EmailSupplier mailsToSend, Session session) {
    List<MailDTO> generatedMails = mailsToSend.generateEmails();
    if (isNotEmpty(generatedMails)) {
      if (session != null) {
        List<TemplateDataDTO> facts = notificationRequestFacts.forSession(session);
        for (MailDTO mail : generatedMails) {
          List<TemplateDataDTO> attributes = new ArrayList<>(mail.getTemplateData());
          attributes.addAll(facts);
          mail.setTemplateData(attributes);
        }
      }
      MailsDTO mailsDTO = new MailsDTO().mails(generatedMails);
      log.info(
          "Sending email notifications with mailDTOs. MailSupplier class: {}",
          mailsToSend.getClass());
      mailService.sendEmailNotification(mailsDTO);
    }
  }

  /**
   * Sends an email notification to the consultant when an enquiry has been assigned to him by a
   * different consultant.
   *
   * @param receiverConsultant the target consultant
   * @param senderUserId the id of initiating user
   * @param askerUserName the name of the asker
   */
  @Async
  public void sendAssignEnquiryEmailNotification(
      Session session,
      Consultant receiverConsultant,
      String senderUserId,
      String askerUserName,
      TenantData tenantData) {
    TenantContext.setCurrentTenantData(tenantData);
    log.info(
        "Preparing to send ASSIGN_ENQUIRY_NOTIFICATION email to consultant: {}",
        receiverConsultant != null ? receiverConsultant.getId() : "No consultant selected");
    try {
      var assignEnquiryEmailSupplier = assignEnquiryEmailSupplierProvider.getObject();
      assignEnquiryEmailSupplier.setReceiverConsultant(receiverConsultant);
      assignEnquiryEmailSupplier.setSenderUserId(senderUserId);
      assignEnquiryEmailSupplier.setAskerUserName(askerUserName);
      sendMailTasksToMailService(assignEnquiryEmailSupplier, session);
    } catch (Exception exception) {
      log.error("EmailNotificationFacade error: ", exception);
    } finally {
      TenantContext.clear();
    }
  }

  @Async
  @Transactional
  public void sendReassignRequestNotification(String matrixRoomId, TenantData tenantData) {
    TenantContext.setCurrentTenantData(tenantData);
    try {
      var session = sessionService.getSessionByMatrixRoomId(matrixRoomId);
      var user = session.getUser();

      if (!shouldSendReassignmentNotificationForAdviceSeeker(user)) {
        log.info(
            "Not sending email notification about reassignment because adviceseeker has this disabled this toggle.");
        return;
      }

      if (hasUserValidEmailAddress(user)) {
        var reassignmentRequestEmailSupplier =
            ReassignmentRequestEmailSupplier.builder()
                .receiverEmailAddress(user.getEmail())
                .receiverLanguageCode(user.getLanguageCode())
                .receiverUsername(user.getUsername())
                .receiverDialect(user.getDialect())
                .tenantTemplateSupplier(tenantTemplateSupplier)
                .applicationBaseUrl(applicationBaseUrl)
                .multiTenancyEnabled(multiTenancyEnabled)
                .build();
        try {
          sendMailTasksToMailService(reassignmentRequestEmailSupplier);
        } catch (Exception exception) {
          log.error(
              "EmailNotificationFacade error: Failed to send reassign request notification",
              exception);
        }
      }
    } finally {
      TenantContext.clear();
    }
  }

  private boolean shouldSendReassignmentNotificationForAdviceSeeker(User user) {
    if (releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) {
      return wantsToReceiveNotificationsAboutReassignment(user);
    }
    return true;
  }

  private boolean hasUserValidEmailAddress(User user) {
    return nonNull(user)
        && isNotBlank(user.getEmail())
        && !user.getEmail().endsWith(identityClientConfig.getEmailDummySuffix());
  }

  @Async
  @Transactional
  public void sendReassignConfirmationNotification(
      ReassignmentNotificationDTO reassignmentNotification, TenantData tenantData) {
    TenantContext.setCurrentTenantData(tenantData);
    try {
      Consultant existingConsultantById =
          findExistingConsultantById(reassignmentNotification.getToConsultantId().toString());

      if (!shouldSendReassignmentNotificationForConsultant(existingConsultantById)) {
        log.info(
            "Not sending email notification about reassignment because consultant has this disabled this toggle");
        return;
      }

      var reassignmentConfirmationEmailSupplier =
          ReassignmentConfirmationEmailSupplier.builder()
              .receiverConsultant(existingConsultantById)
              .senderConsultantName(reassignmentNotification.getFromConsultantName())
              .tenantTemplateSupplier(tenantTemplateSupplier)
              .applicationBaseUrl(applicationBaseUrl)
              .multiTenancyEnabled(multiTenancyEnabled)
              .build();
      try {
        sendMailTasksToMailService(reassignmentConfirmationEmailSupplier);
      } catch (Exception exception) {
        log.error(
            "EmailNotificationFacade error: Failed to send reqssign confiration notification",
            exception);
      }
    } finally {
      TenantContext.clear();
    }
  }

  /** Legacy callers have agency-counselling context; real session producers use the overload. */
  @Async
  public void sendInquiryAcceptedNotification(
      User user, Consultant consultant, TenantData tenantData) {
    sendInquiryAcceptedNotification(user, consultant, tenantData, null);
  }

  @Async
  public void sendInquiryAcceptedNotification(
      User user, Consultant consultant, TenantData tenantData, Session session) {
    TenantContext.setCurrentTenantData(tenantData);
    try {
      if (!hasUserValidEmailAddress(user) || !shouldSendInquiryAcceptedNotification(user)) {
        return;
      }

      emailBrand.platformName();
      Long recipientTenantId = user.getTenantId();
      Long requestTenantId = tenantData == null ? null : tenantData.getTenantId();
      if (requestTenantId == null && !multiTenancyEnabled) {
        requestTenantId = recipientTenantId;
      }
      if (recipientTenantId == null
          || recipientTenantId <= 0
          || requestTenantId == null
          || requestTenantId <= 0) {
        throw new IllegalStateException("Inquiry accepted notification tenant metadata is missing");
      }
      if (!recipientTenantId.equals(requestTenantId)) {
        throw new IllegalStateException(
            "Inquiry accepted notification recipient and request tenants differ");
      }

      var tenant = tenants.getRestrictedTenantDataFresh(requestTenantId);
      if (tenant == null || !requestTenantId.equals(tenant.getId()))
        throw new IllegalStateException("Inquiry accepted tenant is unavailable");
      if (!AskerNotificationChannelPolicy.emailAllowed(session, tenant.getSettings())) return;
      var templateAttributes = new ArrayList<TemplateDataDTO>();
      templateAttributes.add(
          new TemplateDataDTO().key("tenantId").value(requestTenantId.toString()));
      templateAttributes.add(
          new TemplateDataDTO().key("recipientTenantId").value(recipientTenantId.toString()));

      if (!multiTenancyEnabled) {
        templateAttributes.add(new TemplateDataDTO().key("url").value(applicationBaseUrl));
      } else {
        templateAttributes.addAll(tenantTemplateSupplier.getTemplateAttributes());
      }

      var language =
          de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode.fromValue(
              user.getLanguageCode() == null ? "de" : user.getLanguageCode().toString());
      var mailDTO =
          new MailDTO()
              .template(EmailSupplier.TEMPLATE_INQUIRY_ACCEPTED_NOTIFICATION)
              .email(user.getEmail())
              .language(language)
              .dialect(user.getDialect())
              .templateData(templateAttributes);
      mailService.sendEmailNotification(new MailsDTO().mails(List.of(mailDTO)));
    } catch (Exception exception) {
      log.error(
          "EmailNotificationFacade error: Failed to send inquiry accepted notification", exception);
    } finally {
      TenantContext.clear();
    }
  }

  private boolean shouldSendReassignmentNotificationForConsultant(
      Consultant existingConsultantById) {
    if (releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) {
      return wantsToReceiveNotificationsAboutReassignment(existingConsultantById);
    }
    return true;
  }

  private boolean shouldSendInquiryAcceptedNotification(User user) {
    if (releaseToggleService.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)) {
      var notificationSettings = deserializeNotificationSettingsDTOOrDefaultIfNull(user);
      return user.isNotificationsEnabled()
          && !Boolean.FALSE.equals(notificationSettings.getNewChatMessageNotificationEnabled());
    }
    return true;
  }

  private boolean wantsToReceiveNotificationsAboutReassignment(
      NotificationsAware notificationsAware) {
    NotificationsSettingsDTO notificationsSettingsDTO =
        deserializeNotificationSettingsDTOOrDefaultIfNull(notificationsAware);
    return notificationsAware.isNotificationsEnabled()
        && notificationsSettingsDTO.getReassignmentNotificationEnabled();
  }

  private Consultant findExistingConsultantById(String consultantId) {
    return consultantService
        .getConsultant(consultantId)
        .orElseThrow(() -> new NotFoundException("Consultant with id %s not found", consultantId));
  }
}
