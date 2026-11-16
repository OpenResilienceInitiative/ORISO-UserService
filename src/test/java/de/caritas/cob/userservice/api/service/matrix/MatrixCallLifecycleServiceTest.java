package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EnquiryRejectionRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #1656 (security review of #1642): a departure or a shorter membership only removes presence, so
 * {@link MatrixCallLifecycleService#handleRoom} applies it even for a sender it cannot resolve.
 * That is safe only because nobody can shorten a device row somebody else holds. Two guards ensure
 * it: {@link MatrixRtcMembershipEvent#ownedBy} drops an event whose state key names another person,
 * and a device row is only ever replaced by an event of the sender that holds it.
 */
class MatrixCallLifecycleServiceTest {
  private static final String SOURCE = "!group:example";
  private static final String MEDIA = "!media:example";
  private static final String VICTIM = "@victim:example";
  private static final String MALLORY = "@mallory:example";
  private static final String VICTIM_KEY = "_" + VICTIM + "_VICTIMDEVICE";
  private static final String MALLORY_KEY = "_" + MALLORY + "_MALLORYDEVICE";
  private static final long TENANT = 7L;

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private MatrixCallLifecycleService lifecycle;
  private MatrixCallBinding binding;
  private long now;
  private long victimExpiry;

  @BeforeEach
  void setUp() {
    lifecycle =
        new MatrixCallLifecycleService(
            bindings,
            mock(EnquiryRejectionRepository.class),
            matrix,
            conversations,
            users,
            mock(ConsultantRepository.class),
            mock(EventNotificationService.class),
            mock(AccountInactivityService.class),
            mock(MatrixCallGuestService.class));
    now = System.currentTimeMillis();
    victimExpiry = now + 3_600_000;
    binding =
        MatrixCallBinding.builder()
            .sourceRoomId(SOURCE)
            .callId("call")
            .mediaRoomId(MEDIA)
            .callerMatrixId(VICTIM)
            .chatId(45L)
            .tenantId(TENANT)
            .invitedAt(now - 60_000)
            .inviteExpiresAt(now + 60_000)
            .startedAt(now - 50_000)
            .mediaObservedAt(now - 50_000)
            .video(true)
            .build();
    binding
        .getDevices()
        .put(VICTIM_KEY, new MatrixCallDevice(VICTIM, now - 50_000, victimExpiry, true, "$v1"));
    when(bindings.lockByMediaRoomId(MEDIA)).thenReturn(Optional.of(binding));
    when(conversations.resolve(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, null, 45L)));
    when(conversations.lockCurrentForWriting(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(users.findByMatrixUserIdAndDeleteDateIsNull(VICTIM))
        .thenReturn(Optional.of(user("victim", VICTIM)));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(MALLORY))
        .thenReturn(Optional.of(user("mallory", MALLORY)));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(VICTIM, MALLORY)));
  }

  @ParameterizedTest(name = "departure = {0}")
  @ValueSource(booleans = {true, false})
  void aMemberCannotShortenAnotherPersonsDeviceThroughItsStateKey(boolean departure) {
    // Guard 1: the state key names the victim, the sender is Mallory: the event is not parsed.
    lifecycle.handleRoom(MEDIA, batch(shortening(MALLORY, VICTIM_KEY, departure)));

    assertVictimStillPresent(VICTIM_KEY);
  }

  @ParameterizedTest(name = "departure = {0}")
  @ValueSource(booleans = {true, false})
  void aMemberCannotShortenADeviceRowAnotherSenderHoldsUnderTheirOwnKey(boolean departure) {
    // Guard 2: even a key Mallory owns does not let her shorten the row while the victim holds it.
    binding
        .getDevices()
        .put(MALLORY_KEY, new MatrixCallDevice(VICTIM, now - 50_000, victimExpiry, true, "$v2"));
    lifecycle.handleRoom(MEDIA, batch(shortening(MALLORY, MALLORY_KEY, departure)));

    assertVictimStillPresent(MALLORY_KEY);
  }

  @ParameterizedTest(name = "departure = {0}")
  @ValueSource(booleans = {true, false})
  void anUnresolvableSenderCannotShortenADeviceRowAnotherSenderHolds(boolean departure) {
    // The presence-only path for unknown senders needs the row's own sender as well.
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(VICTIM)));
    binding
        .getDevices()
        .put(MALLORY_KEY, new MatrixCallDevice(VICTIM, now - 50_000, victimExpiry, true, "$v2"));
    lifecycle.handleRoom(MEDIA, batch(shortening(MALLORY, MALLORY_KEY, departure)));

    assertVictimStillPresent(MALLORY_KEY);
  }

  @ParameterizedTest(name = "departure = {0}")
  @ValueSource(booleans = {true, false})
  void theHolderThemselvesCanShortenTheirDevice(boolean departure) {
    // Control: the same event from the device's own sender applies.
    lifecycle.handleRoom(MEDIA, batch(shortening(VICTIM, VICTIM_KEY, departure)));

    assertThat(binding.getDevices().get(VICTIM_KEY).getExpiresAt()).isLessThan(victimExpiry);
  }

  private void assertVictimStillPresent(String key) {
    var device = binding.getDevices().get(key);
    assertThat(device.getSenderMatrixId()).isEqualTo(VICTIM);
    assertThat(device.getExpiresAt()).isEqualTo(victimExpiry);
    assertThat(binding.getEndedAt()).as("the call keeps running").isNull();
  }

  private static Map<String, Object> batch(Map<String, Object> event) {
    return Map.of("timeline", Map.of("events", List.of(event)));
  }

  /** A departure (empty content) or a membership that runs out in one second. */
  private Map<String, Object> shortening(String sender, String stateKey, boolean departure) {
    var event = new HashMap<String, Object>();
    event.put("type", MatrixRtcMembershipEvent.TYPE);
    event.put("event_id", "$shortening-" + sender + "-" + departure);
    event.put("sender", sender);
    event.put("state_key", stateKey);
    event.put("origin_server_ts", now - 1_000);
    event.put(
        "content",
        departure
            ? Map.of()
            : Map.of(
                "application",
                "m.call",
                "scope",
                "m.room",
                "call_id",
                "",
                "device_id",
                "DEVICE",
                "expires",
                1_000,
                "focus_active",
                Map.of("type", "livekit"),
                "foci_preferred",
                List.of()));
    return event;
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@example.invalid")
        .tenantId(TENANT)
        .matrixUserId(matrixId)
        .build();
  }
}
