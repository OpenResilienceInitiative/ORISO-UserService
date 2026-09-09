package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_KREUZBUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

class CreateChatMatrixMembershipTest {
  @ParameterizedTest
  @CsvSource({"SELF_HELP,false", "INTERNAL_GROUP,false", "SELF_HELP,true", "INTERNAL_GROUP,true"})
  void selectedColleagueWithoutMatrixMappingIsProvisionedAndJoined(
      ConversationType type, boolean newOwner) throws Exception {
    var chats = mock(ChatService.class);
    var sessions = mock(SessionService.class);
    var agencies = mock(AgencyService.class);
    var converter = mock(ChatConverter.class);
    var matrix = mock(MatrixSynapseService.class);
    var consultants = mock(ConsultantRepository.class);
    var participants = mock(GroupChatParticipantRepository.class);
    var users = mock(UserRepository.class);
    var gateway = mock(SessionRoomGateway.class);
    var helper = mock(UserHelper.class);
    var transcoder = mock(UsernameTranscoder.class);
    var names = mock(ConsultantDisplayNameResolver.class);
    var membership =
        new AgencySilentMembershipService(consultants, gateway, helper, transcoder, names);
    var facade =
        new CreateChatFacade(
            chats,
            sessions,
            agencies,
            converter,
            matrix,
            consultants,
            participants,
            users,
            membership);
    var owner = new Consultant();
    owner.setId("owner");
    owner.setTenantId(40L);
    owner.setUsername("encoded-owner");
    owner.setMatrixUserId(newOwner ? null : "@owner:matrix.org");
    var colleague = new Consultant();
    colleague.setId("colleague");
    colleague.setTenantId(40L);
    colleague.setUsername("encoded-colleague");
    var chat = new Chat();
    chat.setId(300L);
    chat.setConversationType(type);
    var session = new Session();
    session.setId(200L);
    session.setCreateDate(java.time.LocalDateTime.of(2026, 9, 9, 12, 0));
    var dto = mock(ChatDTO.class);
    when(dto.getConsultantIds()).thenReturn(List.of("colleague"));
    when(dto.getAgencyId()).thenReturn(12L);
    when(dto.getTopic()).thenReturn("Call membership regression");
    when(chats.saveChat(any())).thenReturn(chat);
    when(sessions.saveSession(any())).thenReturn(session);
    when(agencies.getAgency(12L)).thenReturn(AGENCY_DTO_KREUZBUND);
    when(converter.convertToEntity(any(), any(), any())).thenReturn(chat);
    when(users.findByUserIdAndDeleteDateIsNull(any())).thenReturn(Optional.of(new User()));
    when(consultants.findById("colleague")).thenReturn(Optional.of(colleague));
    var room = new MatrixCreateRoomResponseDTO();
    room.setRoomId("!group:matrix.org");
    when(matrix.createRoomAsMatrixUser(any(), any(), any())).thenReturn(ResponseEntity.ok(room));
    when(matrix.loginAsUserAccessToken("@owner:matrix.org")).thenReturn("owner-test-token");
    when(transcoder.decodeUsername("encoded-colleague")).thenReturn("colleague");
    when(names.resolveMatrixDisplayName(colleague)).thenReturn("Colleague");
    when(helper.getRandomPassword()).thenReturn("generated-test-password");
    when(transcoder.decodeUsername("encoded-owner")).thenReturn("owner");
    when(names.resolveMatrixDisplayName(owner)).thenReturn("Owner");
    when(gateway.createUser("owner", "generated-test-password", "Owner"))
        .thenReturn("@owner:matrix.org");
    when(gateway.createUser("colleague", "generated-test-password", "Colleague"))
        .thenReturn("@colleague:matrix.org");
    when(gateway.loginAsUser("@colleague:matrix.org")).thenReturn("colleague-test-token");
    when(gateway.joinRoom("!group:matrix.org", "colleague-test-token")).thenReturn(true);

    facade.createChatV2(dto, owner);

    assertThat(colleague.getMatrixUserId()).isEqualTo("@colleague:matrix.org");
    verify(consultants).save(colleague);
    assertThat(owner.getMatrixUserId()).isEqualTo("@owner:matrix.org");
    if (newOwner) verify(consultants).save(owner);
    else verify(consultants, never()).save(owner);
    verify(gateway).inviteUser("!group:matrix.org", "@colleague:matrix.org", "owner-test-token");
    verify(gateway).joinRoom("!group:matrix.org", "colleague-test-token");
    verify(participants)
        .save(
            argThat(
                p ->
                    "colleague".equals(p.getConsultantId())
                        && p.getRole() == GroupChatParticipant.ParticipantRole.CO_MODERATOR));
  }
}
