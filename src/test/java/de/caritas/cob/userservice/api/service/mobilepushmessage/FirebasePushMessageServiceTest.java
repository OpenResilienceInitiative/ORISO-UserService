package de.caritas.cob.userservice.api.service.mobilepushmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.getField;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class FirebasePushMessageServiceTest {

  @InjectMocks private FirebasePushMessageService firebasePushMessageService;

  @Mock private FirebaseMessaging firebaseMessaging;

  @BeforeEach
  public void setup() {
    setField(firebasePushMessageService, "firebaseMessaging", firebaseMessaging);
  }

  @Test
  public void initializeFirebase_Should_notInitialiteFirebaseMessaging_When_firebaseIsDisabled() {
    setField(this.firebasePushMessageService, "isEnabled", false);

    try (var logCaptor = LogbackCaptor.forClass(LogService.class)) {
      assertDoesNotThrow(() -> this.firebasePushMessageService.initializeFirebase());

      Object firebaseMessaging = getField(firebasePushMessageService, "firebaseMessaging");
      assertThat(logCaptor.contains(Level.INFO, "Firebase push notifications are disabled"))
          .isTrue();
    }
  }

  @Test
  public void initializeFirebase_Should_throwException_When_configurationCanNotBeLoaded() {
    assertThrows(
        Exception.class,
        () -> {
          setField(this.firebasePushMessageService, "isEnabled", true);
          this.firebasePushMessageService.initializeFirebase();
        });
  }

  @Test
  public void pushMessage_Should_pushFirebaseMessage() throws FirebaseMessagingException {
    setField(this.firebasePushMessageService, "isEnabled", true);

    try (var logCaptor = LogbackCaptor.forClass(LogService.class)) {
      this.firebasePushMessageService.pushNewMessageEvent("registrationToken");

      verify(this.firebaseMessaging, times(1)).send(any());
      assertThat(logCaptor.events()).isEmpty();
    }
  }

  @Test
  public void pushMessage_Should_logWarning_When_sendFails() throws FirebaseMessagingException {
    setField(this.firebasePushMessageService, "isEnabled", true);
    FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
    when(this.firebaseMessaging.send(any())).thenThrow(exception);

    try (var logCaptor = LogbackCaptor.forClass(LogService.class)) {
      this.firebasePushMessageService.pushNewMessageEvent("registrationToken");

      assertThat(logCaptor.count(Level.WARN)).isEqualTo(1);
    }
  }

  @Test
  public void pushMessage_Should_notSendNotification_When_firebaseIsDisabled()
      throws FirebaseMessagingException {
    setField(this.firebasePushMessageService, "isEnabled", false);

    this.firebasePushMessageService.pushNewMessageEvent("registrationToken");

    verifyNoMoreInteractions(this.firebaseMessaging);
  }
}
