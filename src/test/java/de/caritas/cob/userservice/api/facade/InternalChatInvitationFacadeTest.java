package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalChatRecipientDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.chat.MemberSetLock;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class InternalChatInvitationFacadeTest {

  private static final long CHAT_ID = 7L;
  private static final long SESSION_ID = 70L;
  private static final long AGENCY_ID = 5L;
  private static final String ROOM = "!room:oriso.test";

  @Mock private InternalSubsetChatFacade internalSubsetChatFacade;
  @Mock private ChatRepository chatRepository;
  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MemberSetLock memberSetLock;
  @Mock private ChatAgencyRepository chatAgencyRepository;
  @Mock private PlatformTransactionManager transactions;
  @Mock private GroupChatMembershipService groupChatMembershipService;

  @InjectMocks private InternalChatInvitationFacade facade;

  private final Consultant owner = consultant("owner");
  private final Consultant caller = consultant("caller");
  private final Consultant colleague = consultant("colleague");
  private Chat chat;

  @BeforeEach
  void setUp() {
    chat = new Chat();
    chat.setId(CHAT_ID);
    chat.setMembersOnly(true);
    chat.setActive(true);
    chat.setConversationType(ConversationType.INTERNAL_GROUP);
    chat.setMatrixRoomId(ROOM);
    chat.setChatOwner(owner);
    lenient().when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(chat));
    lenient()
        .when(chatAgencyRepository.findByChat_Id(CHAT_ID))
        .thenReturn(List.of(new ChatAgency(chat, AGENCY_ID)));
    lenient().when(memberSetLock.acquire(any())).thenReturn(() -> {});
    lenient()
        .when(participantRepository.findBySeriesIdAndConsultantId(CHAT_ID, "owner"))
        .thenReturn(Optional.of(participant("owner", ParticipantRole.OWNER)));
    lenient()
        .when(participantRepository.findBySeriesIdAndConsultantId(CHAT_ID, "caller"))
        .thenReturn(Optional.of(participant("caller", ParticipantRole.CO_MODERATOR)));
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull("colleague"))
        .thenReturn(Optional.of(colleague));
    lenient()
        .when(internalSubsetChatFacade.permitsRecipient(owner, colleague, AGENCY_ID))
        .thenReturn(true);
    lenient()
        .when(matrixSynapseService.getRoomHistoryVisibility(ROOM))
        .thenReturn(Optional.of("joined"));
    lenient().when(matrixSynapseService.loginAsUserAccessToken(anyString())).thenReturn("token");
    lenient()
        .when(groupChatMembershipService.ensureOwnerCanInvite(ROOM, "@owner:oriso.test"))
        .thenReturn(true);
    lenient().when(matrixSynapseService.joinRoom(ROOM, "token")).thenReturn(true);
  }

  @Test
  void invite_adds_an_eligible_colleague_in_matrix_and_records_them() throws Exception {
    assertThat(facade.invite(caller, CHAT_ID, "colleague")).isTrue();

    verify(matrixSynapseService).inviteUserToRoom(ROOM, "@colleague:oriso.test", "token");
    verify(matrixSynapseService).joinRoom(ROOM, "token");
    var saved = ArgumentCaptor.forClass(GroupChatParticipant.class);
    verify(participantRepository).save(saved.capture());
    assertThat(saved.getValue().getConsultantId()).isEqualTo("colleague");
    assertThat(saved.getValue().getSeriesId()).isEqualTo(CHAT_ID);
    assertThat(saved.getValue().getChatId()).isEqualTo(SESSION_ID);
    assertThat(saved.getValue().getRole()).isEqualTo(ParticipantRole.CO_MODERATOR);
  }

  @Test
  void invite_checks_the_scope_against_the_room_owner_and_centre_not_the_caller() throws Exception {
    facade.invite(caller, CHAT_ID, "colleague");

    verify(internalSubsetChatFacade).permitsRecipient(owner, colleague, AGENCY_ID);
    verify(internalSubsetChatFacade, never()).permitsRecipient(eq(caller), any(), any());
  }

  @Test
  void invite_refuses_an_outsider_who_takes_no_part_in_the_chat() throws Exception {
    var outsider = consultant("outsider");

    assertThatThrownBy(() -> facade.invite(outsider, CHAT_ID, "colleague"))
        .isInstanceOf(ForbiddenException.class);
    verifyNothingChanged();
  }

  @Test
  void invite_refuses_a_colleague_whose_organisation_does_not_permit_the_contact()
      throws Exception {
    when(internalSubsetChatFacade.permitsRecipient(owner, colleague, AGENCY_ID)).thenReturn(false);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refused -> {
              assertThat(refused.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(refused.getCustomHttpHeaders().get("X-Reason"))
                  .containsExactly(
                      HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE.name());
            });
    verifyNothingChanged();
  }

  @Test
  void invite_answers_an_unknown_id_like_one_outside_the_scope() throws Exception {
    when(consultantRepository.findByIdAndDeleteDateIsNull("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "ghost"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            refused -> assertThat(refused.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    verifyNothingChanged();
  }

  @Test
  void invite_twice_does_not_duplicate_the_participant() throws Exception {
    when(participantRepository.findBySeriesIdAndConsultantId(CHAT_ID, "colleague"))
        .thenReturn(Optional.of(participant("colleague", ParticipantRole.CO_MODERATOR)));

    assertThat(facade.invite(caller, CHAT_ID, "colleague")).isFalse();
    verifyNothingChanged();
  }

  @Test
  void invite_refuses_a_room_that_would_show_earlier_messages() throws Exception {
    when(matrixSynapseService.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("shared"));

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.getReason())
                    .contains(HttpStatusExceptionReason.INTERNAL_CHAT_HISTORY_SHARED));
    verifyNothingChanged();
  }

  @Test
  void invite_treats_an_unreadable_history_policy_as_busy_never_as_hidden() throws Exception {
    when(matrixSynapseService.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            busy -> assertThat(busy.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    verifyNothingChanged();
  }

  @Test
  void invite_records_nobody_when_the_colleague_cannot_join() throws Exception {
    when(matrixSynapseService.joinRoom(ROOM, "token")).thenReturn(false);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            failed -> assertThat(failed.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    verify(participantRepository, never()).save(any());
  }

  @Test
  void invite_refuses_a_chat_that_is_not_members_only() throws Exception {
    chat.setMembersOnly(false);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.getReason())
                    .contains(HttpStatusExceptionReason.INTERNAL_CHAT_NOT_INVITABLE));
    verifyNothingChanged();
  }

  @Test
  void recipients_are_the_rooms_scope_without_the_people_already_in() throws Exception {
    when(participantRepository.findBySeriesId(CHAT_ID))
        .thenReturn(
            List.of(
                participant("owner", ParticipantRole.OWNER),
                participant("caller", ParticipantRole.CO_MODERATOR)));
    when(internalSubsetChatFacade.recipients(owner, AGENCY_ID))
        .thenReturn(
            List.of(
                new InternalChatRecipientDTO().consultantId("caller"),
                new InternalChatRecipientDTO().consultantId("colleague")));

    assertThat(facade.recipients(caller, CHAT_ID))
        .extracting(InternalChatRecipientDTO::getConsultantId)
        .containsExactly("colleague");
  }

  @Test
  void recipients_are_refused_to_an_outsider() throws Exception {
    assertThatThrownBy(() -> facade.recipients(consultant("outsider"), CHAT_ID))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void invite_rechecks_the_feature_switch_of_the_room_before_any_matrix_change() throws Exception {
    doThrow(new ForbiddenException("switched off"))
        .when(internalSubsetChatFacade)
        .requireFeatureEnabled(owner, AGENCY_ID);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOf(ForbiddenException.class);
    verifyNothingChanged();
  }

  @Test
  void invite_refuses_invited_history_because_it_shows_messages_from_the_invitation_on()
      throws Exception {
    when(matrixSynapseService.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("invited"));

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOf(ConflictException.class);
    verifyNothingChanged();
  }

  @Test
  void invite_fails_without_a_row_when_the_owners_invite_power_cannot_be_restored()
      throws Exception {
    when(groupChatMembershipService.ensureOwnerCanInvite(ROOM, "@owner:oriso.test"))
        .thenReturn(false);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            failed -> assertThat(failed.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    verifyNothingChanged();
  }

  @Test
  void invite_takes_the_colleague_out_again_when_the_row_cannot_be_written() throws Exception {
    when(participantRepository.save(any())).thenThrow(new IllegalStateException("db down"));
    when(matrixSynapseService.leaveRoom(ROOM, "token")).thenReturn(true);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            failed -> assertThat(failed.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    verify(matrixSynapseService).leaveRoom(ROOM, "token");
  }

  @Test
  void invite_reports_a_failed_undo_as_a_server_error_never_as_nothing_happened() throws Exception {
    when(participantRepository.save(any())).thenThrow(new IllegalStateException("db down"));
    when(matrixSynapseService.leaveRoom(ROOM, "token")).thenReturn(false);

    assertThatThrownBy(() -> facade.invite(caller, CHAT_ID, "colleague"))
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void recipients_are_refused_for_a_room_that_would_show_earlier_messages() throws Exception {
    when(matrixSynapseService.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("shared"));

    assertThatThrownBy(() -> facade.recipients(caller, CHAT_ID))
        .isInstanceOf(ConflictException.class);
  }

  private void verifyNothingChanged() throws Exception {
    verify(matrixSynapseService, never()).inviteUserToRoom(any(), any(), any());
    verify(participantRepository, never()).save(any());
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId("@" + id + ":oriso.test");
    return consultant;
  }

  private static GroupChatParticipant participant(String consultantId, ParticipantRole role) {
    var row = new GroupChatParticipant();
    row.setChatId(SESSION_ID);
    row.setSeriesId(CHAT_ID);
    row.setConsultantId(consultantId);
    row.setRole(role);
    return row;
  }
}
