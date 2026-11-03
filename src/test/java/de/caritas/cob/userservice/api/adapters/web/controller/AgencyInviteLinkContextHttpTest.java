package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP serialization and error contract using the real service; security is tested separately. */
class AgencyInviteLinkContextHttpTest {
  private final AgencyInviteLinkRepository repository = mock(AgencyInviteLinkRepository.class);
  private final CreateAnonymousEnquiryFacade facade = mock(CreateAnonymousEnquiryFacade.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final de.caritas.cob.userservice.api.service.temporaryaccount
          .TemporaryAccountSettingsService
      temporaryAccountSettings =
          mock(
              de.caritas.cob.userservice.api.service.temporaryaccount
                  .TemporaryAccountSettingsService.class);
  private final de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService
      callShareLinks =
          mock(de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService.class);
  private final de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitePolicy
      directInvitePolicy =
          mock(de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitePolicy.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var topicService = mock(TopicService.class);
    when(directInvitePolicy.allows(any(), any(), any())).thenReturn(true);
    var service =
        new AgencyInviteLinkService(
            repository,
            mock(AuthenticatedUser.class),
            topicService,
            consultants,
            mock(ConsultingTypeService.class),
            mock(AgencyService.class),
            facade,
            mock(de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class),
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class),
            temporaryAccountSettings,
            directInvitePolicy,
            callShareLinks);
    when(temporaryAccountSettings.current())
        .thenReturn(
            new de.caritas.cob.userservice.api.service.temporaryaccount
                .TemporaryAccountSettingsValues(
                15,
                24,
                30,
                true,
                de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode
                    .ALWAYS_PERSISTENT,
                3L));
    mvc =
        MockMvcBuilders.standaloneSetup(new AgencyInviteLinkController(service, topicService))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @AfterEach
  void noSideEffects() {
    verify(repository, org.mockito.Mockito.atLeastOnce()).findByToken("token");
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(facade);
    TenantContext.clear();
  }

  @Test
  void publicContextContainsOnlyEntryMetadata() throws Exception {
    when(repository.findByToken("token")).thenReturn(Optional.of(link()));
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
            {"tenantId":83,"agencyId":null,"consultingTypeId":1,"topicId":11,"chatType":"LIVE_CHAT"}
            """,
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }

  /** #1487: the public context of a call share link follows its live settings. */
  @Test
  void callShareLinkContextFollowsTheLiveSettings() throws Exception {
    var link = callLink();
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    when(callShareLinks.temporaryAccountAllowed(link)).thenReturn(false);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
            {"tenantId":83,"agencyId":99,"consultingTypeId":1,"topicId":11,"chatType":"AUDIO",
             "temporaryAccount":false,"admission":"DIRECT","linkKind":"CALL"}
            """,
                    org.springframework.test.json.JsonCompareMode.STRICT));
    verify(callShareLinks).requireUsable(link);
  }

  @Test
  void callShareLinkOfAnEndedCallReturns410() throws Exception {
    var link = callLink();
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    doThrow(
            new de.caritas.cob.userservice.api.service.agencyinvitelink
                .CallShareLinkEndedException())
        .when(callShareLinks)
        .requireUsable(link);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"CALL_SHARE_LINK_ENDED\"}"));
  }

  @Test
  void callShareLinkWhosePermissionWasWithdrawnReturns410() throws Exception {
    var link = callLink();
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    doThrow(
            new de.caritas.cob.userservice.api.service.agencyinvitelink
                .DirectLinkInactiveException())
        .when(callShareLinks)
        .requireDirectInviteAllowed(link);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"DIRECT_LINK_INACTIVE\"}"));
  }

  private static AgencyInviteLink callLink() {
    return AgencyInviteLink.builder()
        .id(5L)
        .token("token")
        .tenantId(83L)
        .agencyId(99L)
        .consultingTypeId(1)
        .topicId(11L)
        .linkKind("CALL")
        .chatType("AUDIO")
        .anonymity("FULL")
        .consultantId("private-consultant")
        .createdByUserId("private-consultant")
        .createDate(LocalDateTime.now())
        .status("ACTIVE")
        .knock(false)
        .temporaryAccount(true)
        .callBindingId(3L)
        .build();
  }

  @Test
  void missingTokenReturns404() throws Exception {
    mvc.perform(get("/users/invitelinks/token/context")).andExpect(status().isNotFound());
  }

  @Test
  void inactiveTokenReturns400() throws Exception {
    var link = link();
    link.setStatus("USED");
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    mvc.perform(get("/users/invitelinks/token/context")).andExpect(status().isBadRequest());
  }

  @Test
  void expiredTokenReturns400WithoutWrites() throws Exception {
    var link = link();
    link.setExpiresAt(LocalDateTime.now().minusDays(1));
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    mvc.perform(get("/users/invitelinks/token/context")).andExpect(status().isBadRequest());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pausedLiveChatContextReturns410WithReason(boolean signedIn) throws Exception {
    var link = link();
    link.setStatus("DISABLED");
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    var request = get("/users/invitelinks/token/context");
    if (signedIn) request.principal(() -> "existing-user");
    mvc.perform(request)
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"INVITE_LINK_PAUSED\"}"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pausedLiveChatRedeemReturns410WithReason(boolean signedIn) throws Exception {
    var link = link();
    link.setStatus("DISABLED");
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    var request = post("/users/invitelinks/token/redeem");
    if (signedIn) request.principal(() -> "existing-user");
    mvc.perform(request)
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"INVITE_LINK_PAUSED\"}"));
    verify(repository).findByTokenAndStatus("token", "ACTIVE");
  }

  @Test
  void switchedOffDirectLinkReturns410WithReason() throws Exception {
    var link = directLink();
    link.setStatus("DISABLED");
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"DIRECT_LINK_INACTIVE\"}"));
  }

  @Test
  void directLinkContextNamesAdmissionButNotTheOwner() throws Exception {
    when(repository.findByToken("token")).thenReturn(Optional.of(directLink()));
    ownerServes(99L);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
            {"tenantId":83,"agencyId":99,"consultingTypeId":1,"topicId":11,"chatType":"VIDEO",
             "temporaryAccount":true,"admission":"KNOCK","hidePasswordField":true,
             "messageRetention":"ALWAYS_PERSISTENT"}
            """,
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void directLinkWithoutTemporaryAccountsOmitsThePlatformAccountSettings() throws Exception {
    var link = directLink();
    link.setTemporaryAccount(false);
    when(repository.findByToken("token")).thenReturn(Optional.of(link));
    ownerServes(99L);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
            {"tenantId":83,"agencyId":99,"consultingTypeId":1,"topicId":11,"chatType":"VIDEO",
             "temporaryAccount":false,"admission":"KNOCK"}
            """,
                    org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void directLinkWhoseOwnerLeftTheBeratungsstelleReturns410() throws Exception {
    when(repository.findByToken("token")).thenReturn(Optional.of(directLink()));
    ownerServes(12345L);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"DIRECT_LINK_INACTIVE\"}"));
  }

  @Test
  void directLinkWhoseOwnerWasDeletedReturns410() throws Exception {
    when(repository.findByToken("token")).thenReturn(Optional.of(directLink()));
    when(consultants.findByIdAndDeleteDateIsNull("private-consultant"))
        .thenReturn(Optional.empty());
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"DIRECT_LINK_INACTIVE\"}"));
  }

  @Test
  void directLinkWhosePermissionWasWithdrawnReturns410() throws Exception {
    when(repository.findByToken("token")).thenReturn(Optional.of(directLink()));
    ownerServes(99L);
    when(directInvitePolicy.allows(any(), any(), any())).thenReturn(false);
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isGone())
        .andExpect(content().json("{\"reason\":\"DIRECT_LINK_INACTIVE\"}"));
  }

  private void ownerServes(Long agencyId) {
    var owner = new Consultant();
    owner.setId("private-consultant");
    owner.setConsultantAgencies(
        new java.util.HashSet<>(
            java.util.Set.of(ConsultantAgency.builder().id(1L).agencyId(agencyId).build())));
    when(consultants.findByIdAndDeleteDateIsNull("private-consultant"))
        .thenReturn(Optional.of(owner));
  }

  private AgencyInviteLink directLink() {
    var link = link();
    link.setLinkKind("COUNSELLOR");
    link.setChatType("VIDEO");
    link.setTemporaryAccount(true);
    return link;
  }

  private AgencyInviteLink link() {
    return AgencyInviteLink.builder()
        .token("token")
        .tenantId(83L)
        .agencyId(99L)
        .consultingTypeId(1)
        .topicId(11L)
        .chatType("LIVE_CHAT")
        .status("ACTIVE")
        .notes("private admin note")
        .consultantId("private-consultant")
        .createdByUserId("private-creator")
        .createdByUsername("private-username")
        .build();
  }
}
