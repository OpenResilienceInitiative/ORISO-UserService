package de.caritas.cob.userservice.api.service.mobilepushmessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.model.ConsultantMobileToken;
import de.caritas.cob.userservice.api.port.out.ConsultantMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class MobilePushNotificationServiceIT {

  private static final String MOBILE_TOKEN = "matrix-background-mobile-token";

  @Autowired private MobilePushNotificationService mobilePushNotificationService;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ConsultantMobileTokenRepository consultantMobileTokenRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private FirebasePushMessageService firebasePushMessageService;

  private String consultantId;

  @BeforeEach
  void persistConsultantMobileToken() {
    consultantMobileTokenRepository.deleteAll();
    consultantId = consultantRepository.findAll().getFirst().getId();
    transactionTemplate.executeWithoutResult(
        ignored -> {
          var consultant = consultantRepository.findById(consultantId).orElseThrow();
          var token = new ConsultantMobileToken();
          token.setConsultant(consultant);
          token.setMobileAppToken(MOBILE_TOKEN);
          consultantMobileTokenRepository.save(token);
        });
  }

  @Test
  void triggerMobilePushNotificationLoadsLazyTokensInsideItsPublicBoundary() {
    assertDoesNotThrow(
        () -> mobilePushNotificationService.triggerMobilePushNotification(List.of(consultantId)));

    verify(firebasePushMessageService).pushNewMessageEvent(MOBILE_TOKEN);
  }
}
