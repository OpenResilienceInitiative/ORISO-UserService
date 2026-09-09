package de.caritas.cob.userservice.api.service.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.email.NotificationEmailService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.mailservice.generated.ApiClient;
import de.caritas.cob.userservice.mailservice.generated.web.MailsControllerApi;
import de.caritas.cob.userservice.mailservice.generated.web.model.ErrorMailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {
  @Mock private NotificationEmailService notificationEmailService;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private MailsControllerApi mailsControllerApi;
  @Mock private ApiClient apiClient;
  @Mock private MailServiceApiControllerFactory mailServiceApiControllerFactory;
  @InjectMocks private MailService mailService;

  @Test
  void reportsAcceptanceOnlyAfterLocalNotificationDispatchWithoutLegacyHttp() {
    assertThat(mailService.sendEmailNotification(new MailsDTO())).isTrue();
    verify(notificationEmailService).send(any());
    verifyNoInteractions(mailServiceApiControllerFactory, securityHeaderSupplier);
  }

  @Test
  void reportsFailureWhenSmtpRejectsDelivery() {
    doThrow(new SmtpSendException("SMTP unavailable")).when(notificationEmailService).send(any());
    assertThat(mailService.sendEmailNotification(new MailsDTO())).isFalse();
  }

  @Test
  void reportsFailureWhenTheOccasionIsUnsupported() {
    doThrow(new IllegalArgumentException("Unknown occasion"))
        .when(notificationEmailService)
        .send(any());
    assertThat(mailService.sendEmailNotification(new MailsDTO())).isFalse();
  }

  @Test
  void retainsTheSeparateOperatorErrorMailContract() {
    when(mailServiceApiControllerFactory.createControllerApi()).thenReturn(mailsControllerApi);
    when(mailsControllerApi.getApiClient()).thenReturn(apiClient);
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    mailService.sendErrorEmailNotification(new ErrorMailDTO());
    verify(mailsControllerApi).sendErrorMail(any());
    verifyNoInteractions(notificationEmailService);
  }
}
