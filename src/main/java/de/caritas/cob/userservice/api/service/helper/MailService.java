package de.caritas.cob.userservice.api.service.helper;

import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.email.NotificationEmailService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.mailservice.generated.ApiClient;
import de.caritas.cob.userservice.mailservice.generated.web.MailsControllerApi;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Compatibility boundary for notification producers; dispatches notifications through local SMTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

  private final @NonNull NotificationEmailService notificationEmailService;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull MailServiceApiControllerFactory mailServiceApiControllerFactory;

  /**
   * Send an email notification through the existing ORISO design system and SMTP transport.
   *
   * @param mailsDTO the transfer object to be handled in MailService
   * @return whether SMTP accepted every mail in the batch
   */
  public boolean sendEmailNotification(MailsDTO mailsDTO) {
    try {
      notificationEmailService.send(mailsDTO);
      return true;
    } catch (Exception e) {
      log.error("Notification SMTP dispatch failed", e);
      return false;
    }
  }

  private void addSecurityHeaders(MailsControllerApi controllerApi) {
    HttpHeaders header = securityHeaderSupplier.getCsrfHttpHeaders();
    ApiClient apiClient = controllerApi.getApiClient();
    header.forEach((name, value) -> apiClient.addDefaultHeader(name, value.iterator().next()));
  }

  /**
   * Send a error email notification via the MailService to configured error recipients.
   *
   * @param errorMailDTO the transfer object to be handled in MailService
   */
  public void sendErrorEmailNotification(ErrorMailDTO errorMailDTO) {
    MailsControllerApi controllerApi = mailServiceApiControllerFactory.createControllerApi();
    addSecurityHeaders(controllerApi);
    try {
      controllerApi.sendErrorMail(errorMailDTO);
    } catch (Exception e) {
      log.error("MailServiceHelper error: Error while calling the MailService", e);
    }
  }
}
