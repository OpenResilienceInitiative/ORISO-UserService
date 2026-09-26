package de.caritas.cob.userservice.api.service.helper;

import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.email.NotificationMailSender;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.mailservice.generated.ApiClient;
import de.caritas.cob.userservice.mailservice.generated.web.MailsControllerApi;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/** Service class to communicate with the MailService. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull MailServiceApiControllerFactory mailServiceApiControllerFactory;
  private final @NonNull NotificationMailSender notificationMailSender;

  /**
   * Send a email notification via the MailService.
   *
   * @param mailsDTO the transfer object to be handled in MailService
   * @return whether MailService accepted the request
   */
  public boolean sendEmailNotification(MailsDTO mailsDTO) {
    if (mailsDTO != null && mailsDTO.getMails() != null) {
      List<MailDTO> upstream = new ArrayList<>();
      boolean accepted = true;
      for (MailDTO mail : mailsDTO.getMails()) {
        if (mail == null || !NotificationMailSender.supports(mail.getTemplate())) {
          upstream.add(mail);
          continue;
        }
        try {
          notificationMailSender.send(mail);
        } catch (RuntimeException failure) {
          log.error(
              "ORISO notification delivery failed for occasion {}: {}",
              mail.getTemplate(),
              failure.getClass().getSimpleName());
          accepted = false;
        }
      }
      if (upstream.isEmpty() && !mailsDTO.getMails().isEmpty()) {
        return accepted;
      }
      return sendUpstream(new MailsDTO().mails(upstream)) && accepted;
    }
    return sendUpstream(mailsDTO);
  }

  private boolean sendUpstream(MailsDTO mailsDTO) {
    try {
      MailsControllerApi controllerApi = mailServiceApiControllerFactory.createControllerApi();
      addSecurityHeaders(controllerApi);
      controllerApi.sendMails(mailsDTO);
      return true;
    } catch (Exception e) {
      log.error("MailServiceHelper error: Error while calling the MailService", e);
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
