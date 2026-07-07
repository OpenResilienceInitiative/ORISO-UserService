package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAnonymousEnquiryResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.CreateInviteLinkCommand;
import de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.RedeemContext;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class AgencyInviteLinkControllerTest {

  @Mock private AgencyInviteLinkService agencyInviteLinkService;
  @Mock private TopicService topicService;

  private AgencyInviteLinkController controller;

  @BeforeEach
  void setUp() {
    controller = new AgencyInviteLinkController(agencyInviteLinkService, topicService);
  }

  @Test
  void create_validRequest_capturesCommandAndReturnsCreated() {
    // Business reason: invite link creation must persist admin-selected targeting attributes.
    var request = new AgencyInviteLinkController.CreateInviteLinkRequestDTO();
    request.setAgencyId(9L);
    request.setTopicId(99L);
    request.setLinkKind("EXTERNAL_INBOUND");
    request.setChatType("LIVE_CHAT");

    var topic = mock(TopicDTO.class);
    when(topic.getName()).thenReturn("Crisis Support");
    when(topicService.getAllTopicsMap()).thenReturn(Map.of(99L, topic));
    when(agencyInviteLinkService.create(any())).thenReturn(sampleLink());

    var response = controller.create(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals("Crisis Support", response.getBody().getTopicName());
    var captor = ArgumentCaptor.forClass(CreateInviteLinkCommand.class);
    verify(agencyInviteLinkService).create(captor.capture());
    assertEquals(9L, captor.getValue().getAgencyId());
    assertEquals(99L, captor.getValue().getTopicId());
  }

  @Test
  void create_unknownAgency_throwsNotFoundException() {
    // Business reason: admins must get immediate feedback when creating links for missing agencies.
    when(agencyInviteLinkService.create(any())).thenThrow(new NotFoundException("agency missing"));
    assertThrows(
        NotFoundException.class,
        () -> controller.create(new AgencyInviteLinkController.CreateInviteLinkRequestDTO()));
  }

  @Test
  void list_withoutPagination_returnsLegacyArrayFormat() {
    // Business reason: old frontend clients rely on array response shape when no paging params are
    // sent.
    var topic = mock(TopicDTO.class);
    when(topic.getName()).thenReturn("Topic");
    when(topicService.getAllTopicsMap()).thenReturn(Map.of(99L, topic));
    when(agencyInviteLinkService.list(null, null, null, null, 0, 20))
        .thenReturn(new PageImpl<>(List.of(sampleLink())));

    var response = controller.list(null, null, null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertInstanceOf(List.class, response.getBody());
  }

  @Test
  void list_withPagination_returnsPagedWrapper() {
    // Business reason: new frontend needs pagination metadata for incremental table loading.
    when(topicService.getAllTopicsMap()).thenReturn(Map.of());
    when(agencyInviteLinkService.list(null, null, null, null, 1, 5))
        .thenReturn(new PageImpl<>(List.of(sampleLink())));

    var response = controller.list(null, null, null, null, 1, 5);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertInstanceOf(
        AgencyInviteLinkController.PagedInviteLinksResponseDTO.class, response.getBody());
  }

  @Test
  void redeem_existingTokenWithSession_returnsSessionAndMetadata() {
    // Business reason: redeem flow must return immediate session credentials when provisioned by
    // backend.
    var session = mock(CreateAnonymousEnquiryResponseDTO.class);
    when(session.getUserName()).thenReturn("anon");
    when(session.getSessionId()).thenReturn(100L);
    when(session.getAccessToken()).thenReturn("access");
    when(session.getExpiresIn()).thenReturn(60);
    when(session.getRefreshToken()).thenReturn("refresh");
    when(session.getRefreshExpiresIn()).thenReturn(120);
    when(session.getRcUserId()).thenReturn("rc-u");
    when(session.getRcToken()).thenReturn("rc-t");
    when(session.getRcGroupId()).thenReturn("rc-g");
    when(agencyInviteLinkService.redeemWithContext("token-1"))
        .thenReturn(new RedeemContext(session, 7L, 9L, 5, 99L));

    var response = controller.redeem("token-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("anon", response.getBody().getUserName());
    assertEquals(7L, response.getBody().getTenantId());
  }

  @Test
  void redeem_existingTokenWithoutSession_returnsMetadataOnly() {
    // Business reason: metadata-only redemption must still unblock frontends that create sessions
    // later.
    when(agencyInviteLinkService.redeemWithContext("token-2"))
        .thenReturn(new RedeemContext(null, 8L, 10L, 6, 100L));

    var response = controller.redeem("token-2");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(8L, response.getBody().getTenantId());
    assertEquals(null, response.getBody().getUserName());
  }

  @Test
  void createAndList_havePreAuthorizeAnnotation() throws Exception {
    // Business reason: invite link administration endpoints must remain restricted to admin
    // authorities.
    assertHasPreAuthorize("create", AgencyInviteLinkController.CreateInviteLinkRequestDTO.class);
    assertHasPreAuthorize(
        "list", String.class, Long.class, String.class, String.class, Integer.class, Integer.class);
  }

  private void assertHasPreAuthorize(String methodName, Class<?>... paramTypes) throws Exception {
    Method method = AgencyInviteLinkController.class.getMethod(methodName, paramTypes);
    assertNotNull(method.getAnnotation(PreAuthorize.class));
  }

  private static AgencyInviteLink sampleLink() {
    return AgencyInviteLink.builder()
        .id(1L)
        .token("token")
        .tenantId(7L)
        .topicId(99L)
        .linkKind("EXTERNAL_INBOUND")
        .chatType("LIVE_CHAT")
        .anonymity("FULL")
        .status("ACTIVE")
        .build();
  }
}
