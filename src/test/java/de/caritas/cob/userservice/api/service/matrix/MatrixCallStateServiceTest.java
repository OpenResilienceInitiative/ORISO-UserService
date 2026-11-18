package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FE#2209 round 6 (F2): the assigned counsellor's call state hides exactly the bindings their
 * takeover would revive. Every other ended binding without authority stays visible as ended, so the
 * UI never offers a start that {@code bind} refuses.
 */
class MatrixCallStateServiceTest {
  private static final String SOURCE = "!session:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String ASKER = "@asker:example";
  private static final String COLLEAGUE = "@colleague:example";
  private static final long TENANT = 7L;
  private static final long SESSION = 16L;

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final MatrixCallGuestService callGuests = mock(MatrixCallGuestService.class);
  private final MatrixCallObservationService observations =
      mock(MatrixCallObservationService.class);
  private final MatrixCallObservationEndRepository ends =
      mock(MatrixCallObservationEndRepository.class);
  private MatrixCallStateService service;

  @BeforeEach
  void setUp() {
    service =
        new MatrixCallStateService(
            bindings,
            conversations,
            users,
            consultants,
            matrix,
            callGuests,
            observations,
            new SessionCallRevival(consultants, ends),
            new RecordingTransactionManager());
    when(conversations.resolve(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, SESSION, null)));
    var counsellor = new Consultant();
    counsellor.setId("counsellor-id");
    counsellor.setTenantId(TENANT);
    counsellor.setMatrixUserId(COUNSELLOR);
    when(consultants.findByIdAndDeleteDateIsNull("counsellor-id"))
        .thenReturn(Optional.of(counsellor));
    when(consultants.countAnyTenantByMatrixUserId(COUNSELLOR)).thenReturn(1L);
    when(consultants.countAnyTenantByMatrixUserId(COLLEAGUE)).thenReturn(1L);
    // CodeRabbit 4564532660: the asker's own observation of the call id is still live. Whoever
    // reads the observation instead of the binding sees the call as running.
    when(observations.readState(any(), eq("call"), any()))
        .thenReturn(
            Optional.of(
                new MatrixCallStateService.CallState(
                    SOURCE,
                    "call",
                    "!asker-media:example",
                    "video",
                    "running",
                    1L,
                    2L,
                    null,
                    5,
                    List.of(ASKER))));
    when(matrix.getCallRoomMembers(SOURCE))
        .thenReturn(Optional.of(List.of(COUNSELLOR, ASKER, COLLEAGUE)));
    when(callGuests.grantsGuestAuthority(any())).thenReturn(false);
    when(callGuests.isAssignedCounsellor(any(), eq(COUNSELLOR))).thenReturn(true);
    when(ends.findActorMatrixIdsBySourceRoomIdAndCallId(anyString(), anyString()))
        .thenReturn(List.of());
  }

  @Test
  void theCounsellorStillSeesEndedAskerCallsThatTheirTakeoverWouldNotRevive() {
    long now = System.currentTimeMillis();
    assertEnded("ended through the endpoint", binding -> binding.finishExplicitly(now));
    assertEnded("legacy end without a cause", binding -> binding.finish(now));
    assertEnded(
        "started",
        binding -> {
          binding.recordAttendance(now - 1000);
          binding.finishByLifecycle(now);
        });
    assertEnded(
        "observed",
        binding -> {
          binding.recordMediaObservation(now - 1000);
          binding.finishByLifecycle(now);
        });
    assertEnded(
        "devices",
        binding -> {
          binding
              .getDevices()
              .put("_" + ASKER, new MatrixCallDevice(ASKER, 1L, now + 60_000, true, "$e"));
          binding.finishByLifecycle(now);
        });
    when(ends.findActorMatrixIdsBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(List.of(COUNSELLOR));
    assertEnded("the counsellor's own end", binding -> binding.finishByLifecycle(now));
  }

  @Test
  void theCounsellorWhoEndedTheCallDoesNotSeeTheAskersLiveObservationAsRunning() {
    // CodeRabbit 4564532660: the counsellor ended the call through the endpoint (or the end is a
    // legacy one without a cause) while the asker's observation of the same call id is still live.
    long now = System.currentTimeMillis();
    assertEnded("ended through the endpoint", binding -> binding.finishExplicitly(now));
    assertEnded("legacy end without a cause", binding -> binding.finish(now));
  }

  @Test
  void aBindingOfACounsellorMovedToAnotherTenantStaysVisibleAsEnded() {
    // CodeRabbit 4564532702: the tenant-filtered lookup misses them; the any-tenant count does not.
    String moved = "@moved-counsellor:example";
    when(consultants.countAnyTenantByMatrixUserId(moved)).thenReturn(1L);
    var closed = askersBinding(moved);
    closed.finishByLifecycle(System.currentTimeMillis());
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(closed));
    assertThat(service.read(counsellor(), SOURCE, "call"))
        .map(MatrixCallStateService.CallState::state)
        .hasValueSatisfying(state -> assertThat(state).isIn("missed", "ended"));
  }

  @Test
  void theCounsellorDoesNotSeeTheServerClosedAskerCallTheirTakeoverRevives() {
    long now = System.currentTimeMillis();
    // A colleague's and the asker's own ends do not block the revival, so they do not show it.
    when(ends.findActorMatrixIdsBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(List.of(COLLEAGUE, ASKER));
    var closed = askersBinding();
    closed.finishByLifecycle(now);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(closed));

    // They read the call as if no binding existed: the observation's state.
    assertThat(service.read(counsellor(), SOURCE, "call"))
        .map(MatrixCallStateService.CallState::callRoomId)
        .hasValue("!asker-media:example");
    assertThat(service.read(counsellor(), SOURCE, "call"))
        .map(MatrixCallStateService.CallState::state)
        .hasValue("running");
  }

  private void assertEnded(String why, Consumer<MatrixCallBinding> end) {
    var binding = askersBinding();
    end.accept(binding);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(binding));
    assertThat(service.read(counsellor(), SOURCE, "call"))
        .as(why)
        .map(MatrixCallStateService.CallState::state)
        .hasValueSatisfying(state -> assertThat(state).isIn("missed", "ended"));
  }

  private static MatrixCallBinding askersBinding() {
    return askersBinding(ASKER);
  }

  private static MatrixCallBinding askersBinding(String caller) {
    long now = System.currentTimeMillis();
    return MatrixCallBinding.builder()
        .id(42L)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId("!asker-media:example")
        .callerMatrixId(caller)
        .sessionId(SESSION)
        .tenantId(TENANT)
        .invitedAt(now - 5000)
        .inviteExpiresAt(now + 60_000)
        .build();
  }

  private static AuthenticatedUser counsellor() {
    var actor = new AuthenticatedUser();
    actor.setUserId("counsellor-id");
    actor.setTenantId(TENANT);
    actor.setRoles(Set.of("consultant"));
    return actor;
  }
}
