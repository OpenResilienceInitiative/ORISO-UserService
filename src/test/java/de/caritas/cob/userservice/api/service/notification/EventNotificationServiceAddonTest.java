package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class EventNotificationServiceAddonTest {

  @InjectMocks private EventNotificationService eventNotificationService;
  @Mock private EventNotificationRepository eventNotificationRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;

  @Mock
  private org.springframework.beans.factory.ObjectProvider<MatrixCaseReplyActorAuthorizer>
      caseWriterAuthorizer;

  @Mock private IdentityTombstoneService identityTombstoneService;
  @Mock private EventNotificationDeduplicationWriter deduplicationWriter;
  @Mock private MatrixFeedUpdateSignalService feedUpdateSignalService;

  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

  private static final String PARAMS =
      "{\"addon\":\"digisucht-tools\",\"appId\":\"goals\",\"slot\":\"asker.navigation\","
          + "\"sessionId\":null,\"important\":true,\"title\":{\"de\":\"Titel\"},"
          + "\"text\":{\"de\":\"Text\"}}";

  @Test
  void storesAnUnreadSystemEventOfTheRecipientTenantAndReturnsItsId() {
    when(eventNotificationRepository.save(any()))
        .thenAnswer(
            invocation -> {
              EventNotification event = invocation.getArgument(0);
              event.setId(99L);
              return event;
            });

    var id =
        eventNotificationService.createAddonNotification(
            "asker", 3L, "Titel", "Text", PARAMS, "/addons/digisucht-tools/goals", null);

    assertThat(id).isEqualTo(99L);
    var saved = ArgumentCaptor.forClass(EventNotification.class);
    verify(eventNotificationRepository).save(saved.capture());
    var event = saved.getValue();
    assertThat(event.getRecipientUserId()).isEqualTo("asker");
    assertThat(event.getEventType()).isEqualTo("ADDON_NOTIFICATION");
    assertThat(event.getCategory()).isEqualTo(EventNotificationService.CATEGORY_SYSTEM);
    assertThat(event.getTenantId()).isEqualTo(3L);
    assertThat(event.getActionPath()).isEqualTo("/addons/digisucht-tools/goals");
    assertThat(event.getReadDate()).isNull();
    verify(feedUpdateSignalService).signalFeedUpdated("asker");
  }

  @Test
  void feedReturnsTheParamsUnchanged() {
    var stored =
        EventNotification.builder()
            .id(5L)
            .recipientUserId("asker")
            .eventType("ADDON_NOTIFICATION")
            .category("system")
            .title("Titel")
            .text("Text")
            .params(PARAMS)
            .createDate(LocalDateTime.of(2026, 11, 12, 10, 0))
            .build();
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDescIdDesc(
            eq("asker"), any(Pageable.class)))
        .thenReturn(List.of(stored));

    var item = eventNotificationService.getFeed("asker", 0, 20).getItems().get(0);

    assertThat(item.getEventType()).isEqualTo("ADDON_NOTIFICATION");
    assertThat(item.getCategory()).isEqualTo("system");
    assertThat(item.getParams()).isEqualTo(PARAMS);
    assertThat(item.getCreatedAt()).isEqualTo("2026-11-12T10:00Z");
  }
}
