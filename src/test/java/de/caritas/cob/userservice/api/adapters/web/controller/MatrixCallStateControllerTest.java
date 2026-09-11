package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallConversationResolver;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MatrixCallStateControllerTest {
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ChatRepository chats = mock(ChatRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final AuthenticatedUser actor = new AuthenticatedUser();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    actor.setUserId("participant");
    actor.setTenantId(7L);
    when(sessions.findByMatrixRoomId("!source:example"))
        .thenReturn(
            Optional.of(
                Session.builder()
                    .id(45L)
                    .tenantId(7L)
                    .registrationType(Session.RegistrationType.REGISTERED)
                    .postcode("10115")
                    .status(Session.SessionStatus.IN_PROGRESS)
                    .build()));
    when(users.findByUserIdAndDeleteDateIsNull("participant"))
        .thenReturn(
            Optional.of(
                User.builder()
                    .userId("participant")
                    .username("Participant")
                    .email("participant@example.invalid")
                    .tenantId(7L)
                    .matrixUserId("@participant:example")
                    .build()));
    when(matrix.getRoomMembers("!source:example"))
        .thenReturn(Optional.of(List.of("@participant:example")));
    when(bindings.findBySourceRoomIdAndCallId("!source:example", "stable-call"))
        .thenReturn(
            Optional.of(
                MatrixCallBinding.builder()
                    .sourceRoomId("!source:example")
                    .callId("stable-call")
                    .mediaRoomId("!media:example")
                    .sessionId(45L)
                    .tenantId(7L)
                    .video(true)
                    .invitedAt(1000)
                    .startedAt(2000L)
                    .endedAt(127000L)
                    .build()));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new MatrixCallStateController(
                    new MatrixCallStateService(
                        bindings,
                        new MatrixCallConversationResolver(sessions, chats),
                        users,
                        consultants,
                        matrix),
                    actor))
            .build();
  }

  @Test
  void participantCanReadTheSameEndedCallAfterReload() throws Exception {
    for (int reload = 0; reload < 2; reload++) {
      mvc.perform(
              get("/matrix/calls/state")
                  .param("sourceRoomId", "!source:example")
                  .param("callId", "stable-call"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.callId").value("stable-call"))
          .andExpect(jsonPath("$.state").value("ended"))
          .andExpect(jsonPath("$.durationSeconds").value(125));
    }
  }

  @Test
  void groupParticipantCanReloadCallStateThroughTheServiceRoute() throws Exception {
    when(chats.findByMatrixRoomId("!group:example"))
        .thenReturn(
            Optional.of(
                Chat.builder()
                    .id(84L)
                    .topic("Group call")
                    .initialStartDate(java.time.LocalDateTime.of(2026, 9, 11, 12, 0))
                    .startDate(java.time.LocalDateTime.of(2026, 9, 11, 12, 0))
                    .chatOwner(
                        Consultant.builder()
                            .id("owner")
                            .tenantId(7L)
                            .username("owner")
                            .firstName("Test")
                            .lastName("Owner")
                            .email("owner@example.invalid")
                            .build())
                    .build()));
    when(matrix.getRoomMembers("!group:example"))
        .thenReturn(Optional.of(List.of("@participant:example")));
    when(bindings.findBySourceRoomIdAndCallId("!group:example", "group-call"))
        .thenReturn(
            Optional.of(
                MatrixCallBinding.builder()
                    .sourceRoomId("!group:example")
                    .callId("group-call")
                    .mediaRoomId("!group-media:example")
                    .chatId(84L)
                    .tenantId(7L)
                    .invitedAt(1000)
                    .startedAt(2000L)
                    .endedAt(127000L)
                    .build()));
    for (int reload = 0; reload < 2; reload++) {
      mvc.perform(
              get("/service/matrix/calls/state")
                  .param("sourceRoomId", "!group:example")
                  .param("callId", "group-call"))
          .andExpect(status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                  .string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.sourceRoomId").value("!group:example"))
          .andExpect(jsonPath("$.callId").value("group-call"))
          .andExpect(jsonPath("$.callRoomId").value("!group-media:example"))
          .andExpect(jsonPath("$.callType").value("audio"))
          .andExpect(jsonPath("$.state").value("ended"))
          .andExpect(jsonPath("$.durationSeconds").value(125));
    }
  }

  @Test
  void anotherTenantCannotReadAKnownCall() throws Exception {
    actor.setTenantId(8L);
    assertHidden("!source:example", "stable-call");
  }

  @Test
  void consultantParticipantCanReadTheStoredCall() throws Exception {
    actor.setUserId("consultant");
    actor.setRoles(java.util.Set.of("consultant"));
    when(consultants.findByIdAndDeleteDateIsNull("consultant"))
        .thenReturn(
            Optional.of(
                Consultant.builder()
                    .id("consultant")
                    .username("consultant")
                    .firstName("Test")
                    .lastName("Consultant")
                    .email("consultant@example.invalid")
                    .tenantId(7L)
                    .matrixUserId("@consultant:example")
                    .build()));
    when(matrix.getRoomMembers("!source:example"))
        .thenReturn(Optional.of(List.of("@consultant:example")));
    mvc.perform(
            get("/matrix/calls/state")
                .param("sourceRoomId", "!source:example")
                .param("callId", "stable-call"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ended"))
        .andExpect(jsonPath("$.durationSeconds").value(125));
  }

  @Test
  void deletedDomainIdentityCannotReadEvenWhileMatrixMembershipRemains() throws Exception {
    when(users.findByUserIdAndDeleteDateIsNull("participant")).thenReturn(Optional.empty());
    assertHidden("!source:example", "stable-call");
  }

  @Test
  void removedMemberCannotReadAKnownCall() throws Exception {
    when(matrix.getRoomMembers("!source:example")).thenReturn(Optional.of(List.of()));
    assertHidden("!source:example", "stable-call");
  }

  @Test
  void missingMembershipEvidenceDoesNotGrantAccess() throws Exception {
    when(matrix.getRoomMembers("!source:example")).thenReturn(Optional.empty());
    assertHidden("!source:example", "stable-call");
  }

  @Test
  void aCallIdDoesNotGrantAccessInAnotherConversation() throws Exception {
    assertHidden("!other:example", "stable-call");
  }

  private void assertHidden(String room, String callId) throws Exception {
    mvc.perform(get("/matrix/calls/state").param("sourceRoomId", room).param("callId", callId))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
  }
}
