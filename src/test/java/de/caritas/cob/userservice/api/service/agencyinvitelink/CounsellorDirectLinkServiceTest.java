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
            knockService);
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
        .isInstanceOf(ForbiddenException.class);
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

    publicLink("old", "CHAT", "ACTIVE").setExpiresAt(LocalDateTime.now().minusDays(1));
    assertThatThrownBy(() -> service.startConversation("old", asker("a")))
        .isInstanceOf(BadRequestException.class);

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
        .build();
  }

  private static User asker(String id) {
    var user = new User();
    user.setUserId(id);
    user.setTenantId(TENANT);
    return user;
  }
}
