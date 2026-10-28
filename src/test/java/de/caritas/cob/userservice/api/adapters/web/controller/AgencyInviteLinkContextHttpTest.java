package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.conversation.facade.CreateAnonymousEnquiryFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
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
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var topicService = mock(TopicService.class);
    var service =
        new AgencyInviteLinkService(
            repository,
            mock(AuthenticatedUser.class),
            topicService,
            mock(ConsultantRepository.class),
            mock(ConsultingTypeService.class),
            mock(AgencyService.class),
            facade,
            mock(de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class),
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class));
    mvc =
        MockMvcBuilders.standaloneSetup(new AgencyInviteLinkController(service, topicService))
            .setControllerAdvice(new ApiResponseEntityExceptionHandler())
            .build();
  }

  @AfterEach
  void noSideEffects() {
    verify(repository).findByToken("token");
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
    mvc.perform(get("/users/invitelinks/token/context"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
            {"tenantId":83,"agencyId":99,"consultingTypeId":1,"topicId":11,"chatType":"VIDEO",
             "temporaryAccount":true,"admission":"KNOCK"}
            """,
                    org.springframework.test.json.JsonCompareMode.STRICT));
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
