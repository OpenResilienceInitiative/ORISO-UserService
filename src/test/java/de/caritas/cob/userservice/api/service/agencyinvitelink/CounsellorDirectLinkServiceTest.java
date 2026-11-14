package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.CreateCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CounsellorDirectLinkService.UpdateCommand;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounsellorDirectLinkServiceTest {

  private static final long TENANT = 7L;
  private static final long AGENCY = 70L;
  private static final long TOPIC = 11L;
  private static final String OWNER_ID = "owner-1";

  @Mock private AgencyInviteLinkRepository repository;
  @Mock private AgencyService agencyService;
  @Mock private TopicService topicService;
  @Mock private DirectInvitePolicy policy;
  @Mock private CreateNewSessionFacade createNewSessionFacade;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private TenantService tenantService;
  @Mock private DirectCallKnockService knockService;
  @Mock private de.caritas.cob.userservice.api.port.out.SessionRepository sessionRepository;
  @Mock private DirectInvitationService invitations;
  @Mock private DirectInvitationExpiryService expiry;

  @Mock
  private de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository sessionAppointments;

  private CounsellorDirectLinkService service;
  private Consultant owner;

  @BeforeEach
  void setUp() {
    service =
        new CounsellorDirectLinkService(
            repository,
            agencyService,
            topicService,
            policy,
            createNewSessionFacade,
            consultantRepository,
            tenantService,
            knockService,
            sessionRepository,
            invitations,
            sessionAppointments,
            expiry);
    owner = consultantIn(AGENCY);
    when(consultantRepository.findByIdAndDeleteDateIsNull(OWNER_ID)).thenReturn(Optional.of(owner));
    var agency = new AgencyDTO().id(AGENCY).tenantId(TENANT).consultingType(3).postcode("12345");
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency);
    when(topicService.getTopicById(TOPIC)).thenReturn(new TopicDTO().id(TOPIC));
    when(policy.allows(any(), any(), any())).thenReturn(true);
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  // --- create ---

  @ParameterizedTest
  @EnumSource(
      value = InviteLinkChatType.class,
      names = {"CHAT", "AUDIO", "VIDEO"})
  void create_Should_StoreCounsellorLinkForEveryMedium(InviteLinkChatType medium) {
    var link =
        service.create(owner, new CreateCommand(medium, TOPIC, " Flyer Schule ", null, null));

    assertThat(link.getLinkKind()).isEqualTo("COUNSELLOR");
    assertThat(link.getConsultantId()).isEqualTo(OWNER_ID);
    assertThat(link.getChatType()).isEqualTo(medium.name());
    assertThat(link.getTenantId()).isEqualTo(TENANT);
    assertThat(link.getAgencyId()).isEqualTo(AGENCY);
    assertThat(link.getConsultingTypeId()).isEqualTo(3);
    assertThat(link.getName()).isEqualTo("Flyer Schule");
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    assertThat(link.getExpiresAt()).isNull();
    assertThat(link.getToken()).hasSizeGreaterThan(20);
    // ADR-029 grilling 2026-10-27: off for chat, on for calls.
    assertThat(link.isTemporaryAccount()).isEqualTo(medium != InviteLinkChatType.CHAT);
  }

  @Test
  void create_Should_KeepExplicitTemporaryFlag() {
    var link =
        service.create(owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, true, null));
    assertThat(link.isTemporaryAccount()).isTrue();
  }

  @Test
  void create_Should_Refuse_When_DirectInvitationNotAllowed() {
    when(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.create(
                    owner, new CreateCommand(InviteLinkChatType.VIDEO, TOPIC, null, null, null)))
        .isInstanceOf(ForbiddenException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void create_Should_RequireAgency_When_CounsellorWorksForSeveral() {
    owner = consultantIn(AGENCY, 71L);

    assertThatThrownBy(
            () ->
                service.create(
                    owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("agencyId");
  }

  @Test
  void create_Should_RefuseForeignAgency() {
    assertThatThrownBy(
            () ->
                service.create(
                    owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, null, 999L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void create_Should_RequireMediumAndTopic() {
    assertThatThrownBy(
            () -> service.create(owner, new CreateCommand(null, TOPIC, null, null, null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    owner, new CreateCommand(InviteLinkChatType.CHAT, null, null, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void create_Should_RejectUnknownTopicAndLongName() {
    assertThatThrownBy(
            () ->
                service.create(
                    owner, new CreateCommand(InviteLinkChatType.CHAT, 404L, null, null, null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    new CreateCommand(InviteLinkChatType.CHAT, TOPIC, "x".repeat(101), null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  // --- update / delete ---

  @Test
  void update_Should_SwitchOffAndChangeFields_ButKeepMedium() {
    var link = ownLink(1L, "AUDIO", "ACTIVE");
    when(topicService.getTopicById(12L)).thenReturn(new TopicDTO().id(12L));

    var updated = service.update(owner, 1L, new UpdateCommand("Neu", 12L, false, false));

    assertThat(updated.getStatus()).isEqualTo("DISABLED");
    assertThat(updated.getName()).isEqualTo("Neu");
    assertThat(updated.getTopicId()).isEqualTo(12L);
    assertThat(updated.isTemporaryAccount()).isFalse();
    assertThat(updated.getChatType()).isEqualTo("AUDIO");
    assertThat(updated.getUpdateDate()).isNotNull();
    assertThat(updated).isSameAs(link);
  }

  // --- ORISO-Frontend#2142: an optional, informational date and time ---

  @Test
  void create_Should_StoreTheScheduledTime_When_Given() {
    var at = LocalDateTime.of(2026, 11, 20, 13, 30);

    var link =
        service.create(
            owner,
            new CreateCommand(InviteLinkChatType.VIDEO, TOPIC, "Termin Frau M.", null, null, at));

    assertThat(link.getName()).isEqualTo("Termin Frau M.");
    assertThat(link.getScheduledAt()).isEqualTo(at);
  }

  @Test
  void create_Should_LeaveTheScheduledTimeEmpty_When_NotGiven() {
    var link =
        service.create(owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, null, null));

    assertThat(link.getScheduledAt()).isNull();
  }

  @Test
  void update_Should_SetTheScheduledTime_And_KeepIt_When_Absent() {
    var link = ownLink(1L, "AUDIO", "ACTIVE");
    var at = LocalDateTime.of(2026, 12, 1, 9, 0);

    service.update(owner, 1L, new UpdateCommand(null, null, null, null, at, false));
    assertThat(link.getScheduledAt()).isEqualTo(at);

    service.update(owner, 1L, new UpdateCommand("Neu", null, null, null));
    assertThat(link.getScheduledAt()).isEqualTo(at);
  }

  @Test
  void update_Should_RemoveTheScheduledTime_When_Cleared() {
    var link = ownLink(1L, "AUDIO", "ACTIVE");
    link.setScheduledAt(LocalDateTime.of(2026, 12, 1, 9, 0));

    service.update(owner, 1L, new UpdateCommand(null, null, null, null, null, true));

    assertThat(link.getScheduledAt()).isNull();
  }

  @Test
  void update_Should_RefuseATimeAndAClearTogether() {
    ownLink(1L, "AUDIO", "ACTIVE");

    assertThatThrownBy(
            () ->
                service.update(
                    owner,
                    1L,
                    new UpdateCommand(null, null, null, null, LocalDateTime.now(), true)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void update_Should_ReCheckPermission_When_SwitchingOn() {
    ownLink(1L, "VIDEO", "DISABLED");
    when(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(() -> service.update(owner, 1L, new UpdateCommand(null, null, null, true)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void update_Should_SwitchOn_When_Allowed() {
    ownLink(1L, "CHAT", "DISABLED");
    var updated = service.update(owner, 1L, new UpdateCommand(null, null, null, true));
    assertThat(updated.getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void update_And_Delete_Should_Return404_ForLinksOfOthers() {
    when(repository.findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
            eq(2L), eq(OWNER_ID), eq("COUNSELLOR"), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(owner, 2L, new UpdateCommand("x", null, null, null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.delete(owner, 2L)).isInstanceOf(NotFoundException.class);
    verify(repository, never()).delete(any());
  }

  @Test
  void delete_Should_RemoveOnlyTheLink() {
    var link = ownLink(1L, "CHAT", "ACTIVE");
    service.delete(owner, 1L);
    verify(repository).delete(link);
    verifyNoInteractions(createNewSessionFacade);
  }

  // --- start conversation ---

  @Test
  void startConversation_Should_OpenDirectSessionWithOwner() {
    publicLink("tok", "CHAT", "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(
            any(NewRegistrationDto.class), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(500L)
                .status(HttpStatus.CREATED));
    var user = asker("asker-1");

    var started = service.startConversation("tok", user);

    var captor = ArgumentCaptor.forClass(NewRegistrationDto.class);
    verify(createNewSessionFacade).initializeNewSession(captor.capture(), eq(user), anyList());
    var registration = captor.getValue();
    // consultantId routes the session to the owner (contact-link path), never the enquiry inbox.
    assertThat(registration.getConsultantId()).isEqualTo(OWNER_ID);
    assertThat(registration.getAgencyId()).isEqualTo(AGENCY);
    assertThat(registration.getConsultingType()).isEqualTo("3");
    assertThat(registration.getMainTopicId()).isEqualTo(TOPIC);
    assertThat(registration.getPostcode()).isEqualTo("12345");
    assertThat(started.sessionId()).isEqualTo(500L);
    assertThat(started.created()).isTrue();
    assertThat(started.admission()).isEqualTo(InviteLinkAdmission.DIRECT);
  }

  /** A conversation without a Matrix room is a failed step, never a 2xx (API error contract). */
  @Test
  void startConversation_Should_FailWithBadGateway_When_TheRoomCouldNotBeProvisioned() {
    publicLink("tok", "CHAT", "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(new NewRegistrationResponseDto().sessionId(500L).status(HttpStatus.CREATED));

    assertThatThrownBy(() -> service.startConversation("tok", asker("asker-1")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e)
                            .getStatusCode()
                            .value())
                    .isEqualTo(502));
  }

  @Test
  void startConversation_Should_GiveEachPersonTheirOwnConversation() {
    publicLink("tok", "CHAT", "ACTIVE");
    var first = asker("asker-1");
    var second = asker("asker-2");
    when(createNewSessionFacade.initializeNewSession(any(), eq(first), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(501L)
                .status(HttpStatus.CREATED));
    when(createNewSessionFacade.initializeNewSession(any(), eq(second), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(502L)
                .status(HttpStatus.CREATED));

    assertThat(service.startConversation("tok", first).sessionId()).isEqualTo(501L);
    assertThat(service.startConversation("tok", second).sessionId()).isEqualTo(502L);
  }

  @Test
  void startConversation_Should_ReturnExistingConversation_When_PersonComesBack() {
    publicLink("tok", "CHAT", "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .sessionId(501L)
                .matrixRoomId("!room:x")
                .status(HttpStatus.CONFLICT));

    var started = service.startConversation("tok", asker("asker-1"));

    assertThat(started.created()).isFalse();
    assertThat(started.matrixRoomId()).isEqualTo("!room:x");
  }

  @ParameterizedTest
  @EnumSource(
      value = InviteLinkChatType.class,
      names = {"AUDIO", "VIDEO"})
  void startConversation_Should_AnswerKnock_ForCalls(InviteLinkChatType medium) {
    publicLink("tok", medium.name(), "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(503L)
                .status(HttpStatus.CREATED));

    var knock =
        GroupChatJoinRequest.builder().id(77L).status(GroupChatJoinRequest.Status.PENDING).build();
    when(knockService.knock(eq(503L), any(), any(), eq(medium))).thenReturn(knock);

    var started = service.startConversation("tok", asker("a"));

    assertThat(started.admission()).isEqualTo(InviteLinkAdmission.KNOCK);
    assertThat(started.knockId()).isEqualTo(77L);
    assertThat(started.knockStatus()).isEqualTo("PENDING");
  }

  /**
   * FE#1952: the counsellor's list must say where the conversation came from ("Videoanruf",
   * "Audioanruf", "Direktlink") instead of "Mail", so the conversation keeps its link's medium. It
   * is recorded before the knock, so the knock notice already finds it.
   */
  @ParameterizedTest
  @EnumSource(
      value = InviteLinkChatType.class,
      names = {"CHAT", "AUDIO", "VIDEO"})
  void startConversation_Should_RecordTheLinkMediumOnTheConversation(InviteLinkChatType medium) {
    publicLink("tok", medium.name(), "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(505L)
                .status(HttpStatus.CREATED));

    service.startConversation("tok", asker("a"));

    var order = org.mockito.Mockito.inOrder(sessionRepository, knockService);
    order.verify(sessionRepository).recordDirectLinkOrigin(505L, medium.name());
    if (medium != InviteLinkChatType.CHAT) {
      order.verify(knockService).knock(eq(505L), any(), any(), eq(medium));
    }
  }

  /**
   * ORISO-Frontend#2170: the call of a direct-link conversation is called what the link is called,
   * not "Group call for <room id>". The conversation keeps the link's name beside its medium, so a
   * later rename or deletion of the link leaves it as it was.
   */
  @Test
  void startConversation_Should_RecordTheLinkNameOnTheConversation() {
    var link = publicLink("tok", "VIDEO", "ACTIVE");
    link.setName("Flyer Schule");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(506L)
                .status(HttpStatus.CREATED));

    service.startConversation("tok", asker("a"));

    verify(sessionRepository).recordDirectLinkName(506L, "Flyer Schule");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"   "})
  void startConversation_Should_RecordNoName_When_TheLinkHasNone(String name) {
    var link = publicLink("tok", "VIDEO", "ACTIVE");
    link.setName(name);
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(507L)
                .status(HttpStatus.CREATED));

    service.startConversation("tok", asker("a"));

    verify(sessionRepository, never()).recordDirectLinkName(any(), any());
  }

  @Test
  void startConversation_Should_RecordNothing_When_TheRoomCouldNotBeProvisioned() {
    publicLink("tok", "VIDEO", "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(new NewRegistrationResponseDto().sessionId(500L).status(HttpStatus.CREATED));

    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verifyNoInteractions(sessionRepository);
  }

  @Test
  void startConversation_Should_NotKnock_ForChat() {
    publicLink("tok", "CHAT", "ACTIVE");
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .matrixRoomId("!room:example")
                .sessionId(504L)
                .status(HttpStatus.CREATED));

    assertThat(service.startConversation("tok", asker("a")).knockId()).isNull();
    verifyNoInteractions(knockService);
  }

  @Test
  void startConversation_Should_SayNoLongerActive_When_SwitchedOff() {
    publicLink("tok", "CHAT", "DISABLED");

    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectLinkInactiveException.class)
        .hasMessageContaining("no longer active");
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_Refuse_When_PermissionWithdrawn() {
    publicLink("tok", "AUDIO", "ACTIVE");
    when(policy.allows(TENANT, AGENCY, InviteLinkChatType.AUDIO)).thenReturn(false);

    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectLinkInactiveException.class);
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_RefuseOtherTenant_AndNonDirectLinks_AndExpiry() {
    publicLink("tok", "CHAT", "ACTIVE");
    var foreign = asker("a");
    foreign.setTenantId(8L);
    assertThatThrownBy(() -> service.startConversation("tok", foreign))
        .isInstanceOf(ForbiddenException.class);

    publicLink("live", "LIVE_CHAT", "ACTIVE");
    assertThatThrownBy(() -> service.startConversation("live", asker("a")))
        .isInstanceOf(BadRequestException.class);

    // ORISO-Frontend#2252: an expired direct link says so (410 DIRECT_LINK_EXPIRED).
    publicLink("old", "CHAT", "ACTIVE").setExpiresAt(LocalDateTime.now().minusDays(1));
    assertThatThrownBy(() -> service.startConversation("old", asker("a")))
        .isInstanceOf(DirectLinkExpiredException.class);

    assertThatThrownBy(() -> service.startConversation("missing", asker("a")))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_Refuse_ATemporaryAccountOnAnotherLink() {
    publicLink("tok", "CHAT", "ACTIVE").setId(9L);
    var guest = asker("g");
    guest.setTemporaryAccountSnapshot(
        de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot.builder()
            .medium(de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.CHAT)
            .inviteLinkId(10L)
            .build());

    assertThatThrownBy(() -> service.startConversation("tok", guest))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_SayNoLongerActive_When_OwnerLeftTheAgencyOrPlatform() {
    publicLink("tok", "CHAT", "ACTIVE");
    owner.getConsultantAgencies().forEach(ca -> ca.setDeleteDate(LocalDateTime.now()));
    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectLinkInactiveException.class);

    when(consultantRepository.findByIdAndDeleteDateIsNull(OWNER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectLinkInactiveException.class);
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_FailClosed_ForPersonWithoutTenant_AndNonOperationalTenant() {
    publicLink("tok", "CHAT", "ACTIVE");
    var noTenant = asker("a");
    noTenant.setTenantId(null);
    assertThatThrownBy(() -> service.startConversation("tok", noTenant))
        .isInstanceOf(ForbiddenException.class);

    org.mockito.Mockito.doThrow(new ForbiddenException("paused"))
        .when(tenantService)
        .requireOperationalTenant(TENANT);
    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(createNewSessionFacade);
  }

  // --- direct invitation with appointment (ORISO-Frontend#2252) ---

  @Test
  void create_Should_AttachTheAppointment_AndStoreAccessModeAndKnock() {
    var start = LocalDateTime.now().plusDays(1);
    var end = start.plusHours(1);

    var link =
        service.create(
            owner,
            new CreateCommand(
                InviteLinkChatType.VIDEO,
                TOPIC,
                "Termin Frau M.",
                null,
                null,
                null,
                start,
                end,
                120,
                DirectInvitationAccessMode.GUESTS_ONLY,
                false));

    verify(invitations).attachAppointment(link, start, end, 120);
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.GUESTS_ONLY);
    assertThat(link.isTemporaryAccount()).isTrue();
    assertThat(link.isKnock()).isFalse();
  }

  @Test
  void create_Should_StayWithoutAppointmentAndExpiry_When_NoneIsGiven() {
    var link =
        service.create(owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, null, null));

    verify(invitations, never()).attachAppointment(any(), any(), any(), any());
    assertThat(link.getExpiresAt()).isNull();
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.ACCOUNTS_ONLY);
    assertThat(link.isKnock()).isTrue();
  }

  @Test
  void create_Should_KeepTheLegacyTemporarySwitch_AsGuestsOnly() {
    var link =
        service.create(owner, new CreateCommand(InviteLinkChatType.CHAT, TOPIC, null, true, null));

    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.GUESTS_ONLY);
    assertThat(link.isTemporaryAccount()).isTrue();
  }

  @Test
  void legacyLinkWithoutAccessMode_Should_ReadTheTemporarySwitch() {
    var guests = new AgencyInviteLink();
    guests.setTemporaryAccount(true);
    var accounts = new AgencyInviteLink();
    accounts.setTemporaryAccount(false);

    assertThat(guests.effectiveAccessMode()).isEqualTo(DirectInvitationAccessMode.GUESTS_ONLY);
    assertThat(accounts.effectiveAccessMode()).isEqualTo(DirectInvitationAccessMode.ACCOUNTS_ONLY);
  }

  @Test
  void create_Should_RefuseAValidityWithoutAppointment() {
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    new CreateCommand(
                        InviteLinkChatType.CHAT,
                        TOPIC,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        60,
                        null,
                        null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void create_Should_LetAccountsOnlySwitchOffTemporaryAccounts() {
    var link =
        service.create(
            owner,
            new CreateCommand(
                InviteLinkChatType.VIDEO,
                TOPIC,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                DirectInvitationAccessMode.ACCOUNTS_ONLY,
                null));

    assertThat(link.isTemporaryAccount()).isFalse();
  }

  @Test
  void update_Should_ChangeKnockAndAccessMode_AndMoveTheAppointment() {
    var link = ownLink(5L, "VIDEO", "ACTIVE");
    link.setAppointmentId(java.util.UUID.randomUUID());
    var start = LocalDateTime.now().plusDays(3);

    service.update(
        owner,
        5L,
        new UpdateCommand(
            null,
            null,
            null,
            null,
            null,
            false,
            start,
            start.plusHours(1),
            30,
            DirectInvitationAccessMode.ACCOUNTS_ONLY,
            false));

    assertThat(link.isKnock()).isFalse();
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.ACCOUNTS_ONLY);
    assertThat(link.isTemporaryAccount()).isFalse();
    verify(invitations).change(link, start, start.plusHours(1), 30);
  }

  @Test
  void switchingOffOrDeleting_Should_RemoveTheInvitedPeople_OfAnInvitationWithAppointment() {
    var link = ownLink(5L, "VIDEO", "ACTIVE");
    link.setAppointmentId(java.util.UUID.randomUUID());

    service.update(owner, 5L, new UpdateCommand(null, null, null, false));
    verify(expiry).revoke(link);

    service.delete(owner, 5L);
    verify(expiry, org.mockito.Mockito.times(2)).revoke(link);
  }

  @Test
  void switchingOff_Should_LeaveALinkWithoutAppointmentAlone() {
    ownLink(6L, "VIDEO", "ACTIVE");

    service.update(owner, 6L, new UpdateCommand(null, null, null, false));
    service.delete(owner, 6L);

    verify(expiry, never()).revoke(any());
  }

  @Test
  void startConversation_Should_RefuseAPermanentAccount_When_GuestsOnly() {
    publicLink("tok", "VIDEO", "ACTIVE").setAccessMode(DirectInvitationAccessMode.GUESTS_ONLY);

    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectInvitationRefusedException.class)
        .extracting("reason")
        .isEqualTo(DirectInvitationRefusedException.Reason.DIRECT_INVITATION_GUESTS_ONLY);
    verifyNoInteractions(createNewSessionFacade);
  }

  @Test
  void startConversation_Should_RefuseAGuest_When_AccountsOnly() {
    var link = publicLink("tok", "VIDEO", "ACTIVE");
    link.setId(9L);
    link.setAccessMode(DirectInvitationAccessMode.ACCOUNTS_ONLY);
    var guest = asker("g");
    guest.setTemporaryAccountSnapshot(
        de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot.builder()
            .inviteLinkId(9L)
            .build());

    assertThatThrownBy(() -> service.startConversation("tok", guest))
        .isInstanceOf(DirectInvitationRefusedException.class)
        .extracting("reason")
        .isEqualTo(DirectInvitationRefusedException.Reason.DIRECT_INVITATION_ACCOUNTS_ONLY);
  }

  @Test
  void startConversation_Should_LetBothIn_When_Both() {
    var link = publicLink("tok", "CHAT", "ACTIVE");
    link.setId(9L);
    link.setAccessMode(DirectInvitationAccessMode.BOTH);
    when(createNewSessionFacade.initializeNewSession(any(), any(), anyList()))
        .thenReturn(
            new NewRegistrationResponseDto()
                .sessionId(1L)
                .matrixRoomId("!r:x")
                .status(HttpStatus.CREATED));
    var guest = asker("g");
    guest.setTemporaryAccountSnapshot(
        de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot.builder()
            .inviteLinkId(9L)
            .build());

    assertThat(service.startConversation("tok", guest).sessionId()).isEqualTo(1L);
    assertThat(service.startConversation("tok", asker("a")).sessionId()).isEqualTo(1L);
  }

  @Test
  void startConversation_Should_SayExpired_ForATokenReplacedByANewLink() {
    when(repository.findFirstByPreviousToken("replaced"))
        .thenReturn(Optional.of(link("new", "VIDEO", "ACTIVE")));

    assertThatThrownBy(() -> service.startConversation("replaced", asker("a")))
        .isInstanceOf(DirectLinkExpiredException.class);
  }

  @Test
  void startConversation_Should_SayExpired_AfterTheSweepClosedTheInvitation() {
    publicLink("tok", "VIDEO", "EXPIRED");

    assertThatThrownBy(() -> service.startConversation("tok", asker("a")))
        .isInstanceOf(DirectLinkExpiredException.class);
  }

  @Test
  void startConversation_Should_ShareTheAppointmentConversation_AndOpenNoKnockBeforeTheCallOpens() {
    var link = appointmentLink("tok", "VIDEO", LocalDateTime.now().plusHours(2));
    var shared = sharedSession(500L);
    when(invitations.openAppointmentConversation(eq(link), any(), any()))
        .thenReturn(new DirectInvitationService.Opened(shared, true));

    var started = service.startConversation("tok", asker("interpreter"));

    assertThat(started.sessionId()).isEqualTo(500L);
    assertThat(started.matrixRoomId()).isEqualTo("!shared:x");
    assertThat(started.appointmentConversation()).isTrue();
    assertThat(started.callOpensAt()).isNotNull();
    assertThat(started.knockId()).isNull();
    verify(knockService, never()).knock(any(), any(), any(), any());
    verify(createNewSessionFacade, never()).initializeNewSession(any(), any(), anyList());
  }

  @Test
  void startConversation_Should_Knock_FromFifteenMinutesBeforeTheStart() {
    var link = appointmentLink("tok", "VIDEO", LocalDateTime.now().plusMinutes(10));
    var shared = sharedSession(500L);
    when(invitations.openAppointmentConversation(eq(link), any(), any()))
        .thenReturn(new DirectInvitationService.Opened(shared, false));
    var knock =
        GroupChatJoinRequest.builder().id(3L).status(GroupChatJoinRequest.Status.PENDING).build();
    when(knockService.knock(eq(500L), any(), eq(link.getId()), eq(InviteLinkChatType.VIDEO)))
        .thenReturn(knock);

    var started = service.startConversation("tok", asker("interpreter"));

    assertThat(started.knockId()).isEqualTo(3L);
    assertThat(started.admission()).isEqualTo(InviteLinkAdmission.KNOCK);
  }

  @Test
  void startConversation_Should_LetInWithoutKnocking_When_KnockIsOff() {
    var link = appointmentLink("tok", "VIDEO", LocalDateTime.now().plusMinutes(5));
    link.setKnock(false);
    var shared = sharedSession(500L);
    when(invitations.openAppointmentConversation(eq(link), any(), any()))
        .thenReturn(new DirectInvitationService.Opened(shared, false));
    var admitted =
        GroupChatJoinRequest.builder().id(4L).status(GroupChatJoinRequest.Status.ADMITTED).build();
    when(knockService.admitWithoutKnock(eq(500L), any(), eq(link.getId()), any()))
        .thenReturn(admitted);

    var started = service.startConversation("tok", asker("interpreter"));

    assertThat(started.callAdmitted()).isTrue();
    assertThat(started.admission()).isEqualTo(InviteLinkAdmission.DIRECT);
    verify(knockService, never()).knock(any(), any(), any(), any());
  }

  @Test
  void startConversation_Should_CreateTheSharedConversationAsANewOne_ForTheFirstPerson() {
    var link = appointmentLink("tok", "CHAT", LocalDateTime.now().plusDays(1));
    when(invitations.openAppointmentConversation(eq(link), any(), any()))
        .thenAnswer(
            i -> {
              java.util.function.Supplier<NewRegistrationResponseDto> create = i.getArgument(2);
              create.get();
              return new DirectInvitationService.Opened(sharedSession(500L), true);
            });
    when(createNewSessionFacade.initializeAppointmentSession(any(), any()))
        .thenReturn(new NewRegistrationResponseDto().sessionId(500L).matrixRoomId("!shared:x"));

    service.startConversation("tok", asker("first"));

    var registration = ArgumentCaptor.forClass(NewRegistrationDto.class);
    verify(createNewSessionFacade).initializeAppointmentSession(registration.capture(), any());
    assertThat(registration.getValue().getConsultantId()).isEqualTo(OWNER_ID);
    verify(createNewSessionFacade, never()).initializeNewSession(any(), any(), anyList());
    verify(sessionRepository).recordDirectLinkOrigin(500L, "CHAT");
  }

  @Test
  void createForSession_Should_AnswerNotFound_ForAnotherCounsellorsConversation() {
    var session = sharedSession(600L);
    var other = new Consultant();
    other.setId("other");
    session.setConsultant(other);
    when(sessionRepository.findById(600L)).thenReturn(Optional.of(session));

    assertThatThrownBy(
            () ->
                service.createForSession(
                    owner,
                    600L,
                    new CounsellorDirectLinkService.SessionInvitationCommand(
                        java.util.UUID.randomUUID(), null, null, null, null, null, null, null)))
        .isInstanceOf(NotFoundException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void createForSession_Should_BindTheInvitationToTheConversationAndItsAppointment() {
    var session = sharedSession(600L);
    session.setConsultant(owner);
    session.setAgencyId(AGENCY);
    session.setMainTopicId(TOPIC);
    session.setTenantId(TENANT);
    when(sessionRepository.findById(600L)).thenReturn(Optional.of(session));
    var appointment = new de.caritas.cob.userservice.api.model.SessionAppointment();
    appointment.setId(java.util.UUID.randomUUID());
    when(sessionAppointments.findOfSessionForUpdate(appointment.getId(), 600L))
        .thenReturn(Optional.of(appointment));

    var link =
        service.createForSession(
            owner,
            600L,
            new CounsellorDirectLinkService.SessionInvitationCommand(
                appointment.getId(), null, null, null, "Dolmetscherin", 60, null, null));

    assertThat(link.isJoinsExistingSession()).isTrue();
    assertThat(link.getChatType()).isEqualTo("VIDEO");
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.BOTH);
    assertThat(link.getTopicId()).isEqualTo(TOPIC);
    verify(invitations).attachExistingAppointment(link, appointment, 60);
  }

  @Test
  void createForSession_Should_RequireExactlyOneAppointmentSource() {
    assertThatThrownBy(
            () ->
                service.createForSession(
                    owner,
                    600L,
                    new CounsellorDirectLinkService.SessionInvitationCommand(
                        null, null, null, null, null, null, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  private AgencyInviteLink appointmentLink(String token, String medium, LocalDateTime start) {
    var link = publicLink(token, medium, "ACTIVE");
    link.setId(21L);
    link.setAppointmentId(java.util.UUID.randomUUID());
    link.setAccessMode(DirectInvitationAccessMode.BOTH);
    link.setExpiresAt(start.plusDays(2));
    when(invitations.scheduleOf(link))
        .thenReturn(
            Optional.of(
                DirectInvitationSchedule.of(
                    start
                        .atZone(java.time.ZoneId.systemDefault())
                        .withZoneSameInstant(java.time.ZoneOffset.UTC)
                        .toLocalDateTime(),
                    start
                        .plusHours(1)
                        .atZone(java.time.ZoneId.systemDefault())
                        .withZoneSameInstant(java.time.ZoneOffset.UTC)
                        .toLocalDateTime(),
                    60)));
    return link;
  }

  private static de.caritas.cob.userservice.api.model.Session sharedSession(long id) {
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setId(id);
    session.setMatrixRoomId("!shared:x");
    session.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS);
    return session;
  }

  // --- fixtures ---

  private Consultant consultantIn(Long... agencyIds) {
    var consultant = new Consultant();
    consultant.setId(OWNER_ID);
    consultant.setUsername("owner");
    consultant.setTenantId(TENANT);
    var memberships = new java.util.HashSet<ConsultantAgency>();
    for (Long agencyId : agencyIds) {
      memberships.add(
          ConsultantAgency.builder()
              .id(agencyId)
              .agencyId(agencyId)
              .consultant(consultant)
              .build());
    }
    consultant.setConsultantAgencies(memberships);
    return consultant;
  }

  private AgencyInviteLink ownLink(Long id, String medium, String status) {
    var link = link("own-" + id, medium, status);
    link.setId(id);
    when(repository.findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
            eq(id), eq(OWNER_ID), eq("COUNSELLOR"), eq(InviteLinkChatType.DIRECT_MEDIA)))
        .thenReturn(Optional.of(link));
    return link;
  }

  private AgencyInviteLink publicLink(String token, String medium, String status) {
    var link = link(token, medium, status);
    when(repository.findByToken(token)).thenReturn(Optional.of(link));
    return link;
  }

  private static AgencyInviteLink link(String token, String medium, String status) {
    return AgencyInviteLink.builder()
        .token(token)
        .tenantId(TENANT)
        .agencyId(AGENCY)
        .consultingTypeId(3)
        .topicId(TOPIC)
        .linkKind("LIVE_CHAT".equals(medium) ? "TENANT" : "COUNSELLOR")
        .chatType(medium)
        .anonymity("FULL")
        .consultantId(OWNER_ID)
        .status(status)
        .temporaryAccount(!"CHAT".equals(medium))
        // These links admit the account holders the tests open them with; a legacy link without
        // an access mode reads GUESTS_ONLY from the temporary switch (see the legacy tests).
        .accessMode(DirectInvitationAccessMode.BOTH)
        .build();
  }

  private static User asker(String id) {
    var user = new User();
    user.setUserId(id);
    user.setTenantId(TENANT);
    return user;
  }
}
