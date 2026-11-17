package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frank, 17 Nov 2026: only counsellors start calls; advice seekers and guests only join a call a
 * counsellor started. "Started by a counsellor and running" is read from server-side records only.
 */
class CounsellorStartedCallTest {
  private static final String SOURCE = "!source:example";
  private static final String MEDIA = "!media:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String ASKER = "@asker:example";
  private static final long TENANT = 7L;
  private static final long NOW = 1_000_000L;

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallObservationRepository observations =
      mock(MatrixCallObservationRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final AccountInactivityService accounts = mock(AccountInactivityService.class);
  private final CounsellorStartedCall calls =
      new CounsellorStartedCall(bindings, observations, consultants, accounts);

  @BeforeEach
  void setUp() {
    lenient().when(bindings.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    lenient().when(observations.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(List.of());
    lenient()
        .when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(COUNSELLOR))
        .thenReturn(List.of(consultant("c1", TENANT)));
    lenient().when(accounts.snapshot(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void aCounsellorOfTheTraegerIsACounsellor() {
    assertThat(calls.isCounsellor(COUNSELLOR, TENANT)).isTrue();
  }

  @Test
  void anAskerIsNoCounsellor() {
    assertThat(calls.isCounsellor(ASKER, TENANT)).isFalse();
  }

  @Test
  void aCounsellorOfAnotherTraegerIsNoCounsellorHere() {
    assertThat(calls.isCounsellor(COUNSELLOR, 8L)).isFalse();
  }

  @Test
  void aMatrixIdHeldByTwoAccountsOfTheTraegerStandsForNobody() {
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(COUNSELLOR))
        .thenReturn(List.of(consultant("c1", TENANT), consultant("c2", TENANT)));

    assertThat(calls.isCounsellor(COUNSELLOR, TENANT)).isFalse();
  }

  @Test
  void noRecordOfTheCallRoomMeansNoRunningCall() {
    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void noCallRoomMeansNoRunningCall() {
    assertThat(calls.isRunning(SOURCE, null, TENANT, NOW)).isFalse();
    assertThat(calls.isRunning(SOURCE, " ", TENANT, NOW)).isFalse();
  }

  @Test
  void aRingingCallOfTheCounsellorIsRunning() {
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(ringing(COUNSELLOR)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void aConnectedCallOfTheCounsellorIsRunning() {
    var binding = ringing(COUNSELLOR);
    binding.recordAttendance(NOW - 10);
    binding.getDevices().put("_c_D", device(COUNSELLOR, NOW + 60_000));
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(binding));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void theAskersOwnCallIsNoCounsellorCall() {
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(ringing(ASKER)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anEndedCounsellorCallIsNotRunning() {
    var binding = ringing(COUNSELLOR);
    binding.finishByLifecycle(NOW - 1);
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(binding));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCounsellorCallWhoseInviteRanOutUnansweredIsNotRunning() {
    var binding = ringing(COUNSELLOR, SOURCE, TENANT, NOW - 1);
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(binding));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallRoomOfAnotherConversationIsNotThisConversationsCall() {
    when(bindings.findByMediaRoomId(MEDIA))
        .thenReturn(Optional.of(ringing(COUNSELLOR, "!other:example", TENANT)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallOfAnotherTraegerIsNotRunningHere() {
    when(bindings.findByMediaRoomId(MEDIA))
        .thenReturn(Optional.of(ringing(COUNSELLOR, SOURCE, 8L)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallStartedByASuspendedCounsellorDoesNotCount() {
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(ringing(COUNSELLOR)));
    when(accounts.snapshot("c1"))
        .thenReturn(
            Optional.of(
                new AccountInactivityService.Snapshot(
                    "c1",
                    TENANT,
                    24,
                    0,
                    null,
                    null,
                    AccountInactivityService.Status.SUSPENDED,
                    0,
                    null)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  /** Encrypted group and 1:1 calls are known through the creator's verified registration. */
  @Test
  void aLiveObservationOfTheCounsellorsCallIsRunning() {
    when(observations.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(observation(COUNSELLOR)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void anObservationOfTheAskersCallDoesNotCount() {
    when(observations.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(observation(ASKER)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anEndedObservationDoesNotCount() {
    var observation = observation(COUNSELLOR);
    observation.finishAt(NOW - 1);
    when(observations.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(observation));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anObservationOfAnotherConversationDoesNotCount() {
    var observation = observation(COUNSELLOR, "!other:example");
    when(observations.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(observation));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  /** The binding is the authority of its call room: a lingering observation cannot reopen it. */
  @Test
  void anEndedBindingIsNotReopenedByALingeringObservation() {
    var binding = ringing(COUNSELLOR);
    binding.finishExplicitly(NOW - 1);
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(binding));
    lenient()
        .when(observations.findByMediaRoomId(MEDIA))
        .thenReturn(Optional.of(observation(COUNSELLOR)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  private static MatrixCallBinding ringing(String caller) {
    return ringing(caller, SOURCE, TENANT);
  }

  private static MatrixCallBinding ringing(String caller, String source, long tenant) {
    return ringing(caller, source, tenant, NOW + 60_000);
  }

  private static MatrixCallBinding ringing(
      String caller, String source, long tenant, long inviteExpiresAt) {
    return MatrixCallBinding.builder()
        .id(1L)
        .sourceRoomId(source)
        .callId("call")
        .mediaRoomId(MEDIA)
        .callerMatrixId(caller)
        .sessionId(45L)
        .tenantId(tenant)
        .invitedAt(NOW - 1_000)
        .inviteExpiresAt(inviteExpiresAt)
        .video(true)
        .build();
  }

  private static MatrixCallObservation observation(String creator) {
    return observation(creator, SOURCE);
  }

  private static MatrixCallObservation observation(String creator, String source) {
    return MatrixCallObservation.builder()
        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(source)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(creator)
        .tenantId(TENANT)
        .chatId(3L)
        .registeredAt(NOW - 1_000)
        .lastSeenAt(NOW - 1_000)
        .expiresAt(NOW + 60_000)
        .build();
  }

  private static MatrixCallDevice device(String sender, long expiresAt) {
    return new MatrixCallDevice(sender, NOW - 10, expiresAt, true, "$e" + sender);
  }

  private static Consultant consultant(String id, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenant);
    consultant.setMatrixUserId(COUNSELLOR);
    return consultant;
  }
}
