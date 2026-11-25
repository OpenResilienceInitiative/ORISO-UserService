package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Frank, 17 Nov 2026: only counsellors start calls; advice seekers and guests only join a call a
 * counsellor started, and only while a counsellor is in it right now (the rule guest seats in 1:1
 * calls already follow). Read from server-side records and Matrix state only.
 */
class CounsellorStartedCallTest {
  private static final String SOURCE = "!source:example";
  private static final String MEDIA = "!media:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String COLLEAGUE = "@colleague:example";
  private static final String FOREIGN = "@foreign:example";
  private static final String ASKER = "@asker:example";
  private static final long TENANT = 7L;
  private static final long NOW = System.currentTimeMillis();

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository guests =
      mock(de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository.class);
  private final MatrixCallObservationRepository observations =
      mock(MatrixCallObservationRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final AccountInactivityService accounts = mock(AccountInactivityService.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final CounsellorStartedCall calls =
      new CounsellorStartedCall(bindings, guests, observations, consultants, accounts, matrix);

  @BeforeEach
  void setUp() {
    lenient().when(bindings.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    lenient().when(observations.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(List.of());
    counsellor(COUNSELLOR, "c1", TENANT);
    counsellor(COLLEAGUE, "c2", TENANT);
    counsellor(FOREIGN, "c3", 8L);
    lenient().when(accounts.snapshot(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(matrix.getCallRoomMembers(MEDIA))
        .thenReturn(Optional.of(List.of(COUNSELLOR, ASKER)));
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
        .thenReturn(
            List.of(consultant(COUNSELLOR, "c1", TENANT), consultant(COUNSELLOR, "c9", TENANT)));

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

  // --- bound calls: the listener's observed MatrixRTC memberships ---------------------------

  @Test
  void aCallWithTheCounsellorInItIsJoinable() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000), device(ASKER, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void aCallStillRingingWithNoCounsellorInItIsNotJoinable() {
    bound(call(COUNSELLOR));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallEveryCounsellorLeftIsNotJoinable() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW - 1), device(ASKER, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  /** ORISO-Frontend#2356: a colleague seated as a guest does not keep the call open for others. */
  @Test
  void aCounsellorSeatedAsGuestDoesNotKeepTheCallJoinable() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW - 1), device(COLLEAGUE, NOW + 60_000)));
    when(guests.existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(1L, COLLEAGUE)).thenReturn(true);

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anotherCounsellorOfTheTraegerInTheCallKeepsItJoinable() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW - 1), device(COLLEAGUE, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void aCounsellorOfAnotherTraegerInTheCallDoesNotCount() {
    bound(call(COUNSELLOR, device(FOREIGN, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aSuspendedCounsellorInTheCallDoesNotCount() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000)));
    suspend("c1");

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  /** Once the call room's observation is lost its memberships may be stale: nobody counts. */
  @Test
  void anUnobservedCallRoomProvesNobodyIsInIt() {
    var call = call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000));
    call.loseMediaObservation();
    bound(call);

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void theAskersOwnCallIsNoCounsellorCallEvenWithACounsellorInIt() {
    bound(call(ASKER, device(COUNSELLOR, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anEndedCounsellorCallIsNotJoinable() {
    var call = call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000));
    call.finishByLifecycle(NOW - 1);
    bound(call);

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallRoomOfAnotherConversationIsNotThisConversationsCall() {
    bound(call(COUNSELLOR, "!other:example", TENANT, device(COUNSELLOR, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aCallOfAnotherTraegerIsNotRunningHere() {
    bound(call(COUNSELLOR, SOURCE, 8L, device(COUNSELLOR, NOW + 60_000)));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void aBindingIsAnsweredWithoutAskingMatrix() {
    bound(call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000)));

    calls.isRunning(SOURCE, MEDIA, TENANT, NOW);

    verify(matrix, never()).getCallRoomState(anyString(), anyString());
  }

  /** The binding is the authority of its call room: a lingering observation cannot reopen it. */
  @Test
  void anEndedBindingIsNotReopenedByALingeringObservation() {
    var call = call(COUNSELLOR, device(COUNSELLOR, NOW + 60_000));
    call.finishExplicitly(NOW - 1);
    bound(call);
    lenient()
        .when(observations.findByMediaRoomId(MEDIA))
        .thenReturn(Optional.of(observation(COUNSELLOR)));
    lenient()
        .when(matrix.getCallRoomState(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(List.of(membership(COUNSELLOR, NOW - 1_000))));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  // --- encrypted calls known through their creator's registration: live Matrix state -------

  @Test
  void anObservedCallWithTheCounsellorInItIsJoinable() {
    observed(observation(COUNSELLOR));
    when(matrix.getCallRoomState(MEDIA, COUNSELLOR))
        .thenReturn(
            Optional.of(
                List.of(membership(COUNSELLOR, NOW - 1_000), membership(ASKER, NOW - 500))));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void anObservedCallWithOnlyAskersInItIsNotJoinable() {
    observed(observation(COUNSELLOR));
    when(matrix.getCallRoomState(MEDIA, COUNSELLOR))
        .thenReturn(
            Optional.of(List.of(departure(COUNSELLOR, NOW - 2_000), membership(ASKER, NOW - 500))));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anObservedCallIsReadByAnotherMemberOnceTheCreatorLeftTheCallRoom() {
    observed(observation(COUNSELLOR));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(ASKER, COLLEAGUE)));
    when(matrix.getCallRoomState(MEDIA, ASKER))
        .thenReturn(Optional.of(List.of(membership(COLLEAGUE, NOW - 1_000))));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isTrue();
  }

  @Test
  void anUnreadableCallRoomAdmitsNobody() {
    observed(observation(COUNSELLOR));
    when(matrix.getCallRoomState(MEDIA, COUNSELLOR))
        .thenThrow(new MatrixSynapseService.CallLookupUnavailableException("down"));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anUnknownCallRoomMembershipAdmitsNobody() {
    observed(observation(COUNSELLOR));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.empty());

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anObservationOfTheAskersCallDoesNotCount() {
    observed(observation(ASKER));
    lenient()
        .when(matrix.getCallRoomState(anyString(), anyString()))
        .thenReturn(Optional.of(List.of(membership(COUNSELLOR, NOW - 1_000))));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anEndedObservationDoesNotCount() {
    var observation = observation(COUNSELLOR);
    observation.finishAt(NOW - 1);
    observed(observation);

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  @Test
  void anObservationOfAnotherConversationDoesNotCount() {
    observed(observation(COUNSELLOR, "!other:example"));

    assertThat(calls.isRunning(SOURCE, MEDIA, TENANT, NOW)).isFalse();
  }

  // --- fixtures ----------------------------------------------------------------------------

  private void bound(MatrixCallBinding call) {
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(call));
  }

  private void observed(MatrixCallObservation observation) {
    when(observations.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(observation));
  }

  private static MatrixCallBinding call(String caller, MatrixCallDevice... devices) {
    return call(caller, SOURCE, TENANT, devices);
  }

  private static MatrixCallBinding call(
      String caller, String source, long tenant, MatrixCallDevice... devices) {
    var call =
        MatrixCallBinding.builder()
            .id(1L)
            .sourceRoomId(source)
            .callId("call")
            .mediaRoomId(MEDIA)
            .callerMatrixId(caller)
            .sessionId(45L)
            .tenantId(tenant)
            .invitedAt(NOW - 1_000)
            .inviteExpiresAt(NOW + 60_000)
            .mediaObservedAt(NOW - 1_000)
            .video(true)
            .build();
    if (devices.length > 0) call.recordAttendance(NOW - 900);
    for (var device : devices) call.getDevices().put("_" + device.getSenderMatrixId(), device);
    return call;
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
    return new MatrixCallDevice(sender, NOW - 900, expiresAt, true, "$e" + sender);
  }

  private static Map<String, Object> membership(String sender, long at) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$m" + sender + at,
        "sender",
        sender,
        "state_key",
        "_" + sender + "_DEVICE",
        "origin_server_ts",
        at,
        "content",
        Map.of(
            "application",
            "m.call",
            "scope",
            "m.room",
            "call_id",
            "",
            "device_id",
            "DEVICE",
            "focus_active",
            Map.of("type", "livekit"),
            "foci_preferred",
            List.of(),
            "expires",
            3_600_000L));
  }

  private static Map<String, Object> departure(String sender, long at) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$d" + sender + at,
        "sender",
        sender,
        "state_key",
        "_" + sender + "_DEVICE",
        "origin_server_ts",
        at,
        "content",
        Map.of());
  }

  private void counsellor(String matrixId, String id, long tenant) {
    lenient()
        .when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(matrixId))
        .thenReturn(List.of(consultant(matrixId, id, tenant)));
  }

  private void suspend(String id) {
    when(accounts.snapshot(id))
        .thenReturn(
            Optional.of(
                new AccountInactivityService.Snapshot(
                    id,
                    TENANT,
                    24,
                    0,
                    null,
                    null,
                    AccountInactivityService.Status.SUSPENDED,
                    0,
                    null)));
  }

  private static Consultant consultant(String matrixId, String id, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenant);
    consultant.setMatrixUserId(matrixId);
    return consultant;
  }
}
