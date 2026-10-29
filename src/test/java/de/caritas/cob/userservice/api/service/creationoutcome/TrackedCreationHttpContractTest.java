package de.caritas.cob.userservice.api.service.creationoutcome;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.caritas.cob.userservice.api.adapters.web.controller.UserAdminController;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real HTTP/controller contract; the creation facade is controlled, not a native proof. */
class TrackedCreationHttpContractTest {
  static final String KEY_HEADER = "X-ORISO-Creation-Operation-Key";
  static final String RECEIPT_HEADER = "X-ORISO-Creation-Operation";
  private final Map<Class<?>, Object> dependencies = new HashMap<>();
  private MockMvc mvc;
  private final java.util.concurrent.atomic.AtomicInteger freshCreations =
      new java.util.concurrent.atomic.AtomicInteger();

  @SuppressWarnings("unchecked")
  private <T> T dependency(Class<T> type) {
    return (T) dependencies.computeIfAbsent(type, key -> mock(type));
  }

  @BeforeEach
  void existingCreationRoute() throws Exception {
    var constructor = UserAdminController.class.getConstructors()[0];
    var args = Arrays.stream(constructor.getParameterTypes()).map(this::dependency).toArray();
    var controller = (UserAdminController) constructor.newInstance(args);
    when(dependency(ConsultantAdminFacade.class).createNewConsultant(any()))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id(UUID.randomUUID().toString())));
    var caller = dependency(AuthenticatedUser.class);
    when(caller.isPlatformAdmin()).thenReturn(true);
    when(caller.getUserId()).thenReturn("66aa68bf-54e7-4fe0-9e0b-9084354d2bdd");
    when(caller.getRoles()).thenReturn(Set.of("tenant-admin", "user-admin"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "synthetic-owner",
                "",
                List.of(new SimpleGrantedAuthority(AuthorityValue.CONSULTANT_CREATE))));
    var access =
        new CreationOperationAccess(
            caller,
            dependency(de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class));
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "creationOperationAccess", access);
    var writer = dependency(CreationOperationWriter.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "creationOperationWriter", writer);
    var receipts = new java.util.HashMap<UUID, CreationOperation.Receipt>();
    when(dependency(ConsultantAdminFacade.class).createNewConsultant(any(), any()))
        .thenAnswer(
            call -> {
              CreationOperationContext context = call.getArgument(1);
              var previous = receipts.get(context.candidate().operationKey());
              if (previous != null) {
                context.bind(previous);
                throw new CreationOperationAccess.ExistingOperation(previous.operationId(), false);
              }
              freshCreations.incrementAndGet();
              var now = java.time.Instant.now();
              var receipt =
                  new CreationOperation.Receipt(
                      context.candidate().operationId(),
                      context.candidate().operationKey(),
                      context.candidate().ownerId(),
                      new CreationOperation.Scope(7L, List.of(), "COUNSELLOR"),
                      (short) 1,
                      "a".repeat(64),
                      3L,
                      now,
                      now,
                      now,
                      now,
                      "synthetic-native",
                      CreationOperation.DispatchState.NATIVE_ID_CAPTURED,
                      CreationOperation.WorkflowState.RETURNED,
                      CreationOperation.RowState.COMMITTED,
                      CreationOperation.OnboardingState.NOT_ATTEMPTED,
                      CreationOperation.CompensationState.NOT_STARTED,
                      CreationOperation.Reason.NONE);
              receipts.put(context.candidate().operationKey(), receipt);
              context.bind(receipt);
              return new TrackedConsultantCreation(
                  new ConsultantAdminResponseDTO()
                      .embedded(new ConsultantDTO().id("synthetic-native")),
                  receipt.operationId());
            });
    when(writer.transition(any(), anyLong(), any()))
        .thenAnswer(
            call ->
                receipts.values().stream()
                    .filter(r -> r.operationId().equals(call.getArgument(0)))
                    .findFirst()
                    .orElseThrow());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setHandlerExceptionResolvers(
                new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .ConsultantCreationExceptionResolver())
            .addFilters(
                (request, response, chain) -> {
                  org.springframework.test.util.ReflectionTestUtils.setField(
                      controller, "servletResponse", response);
                  chain.doFilter(request, response);
                })
            .build();
  }

  @AfterEach
  void clearRequestOnlyState() {
    SecurityContextHolder.clearContext();
    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.clear();
  }

  private org.springframework.test.web.servlet.ResultActions create(String key) throws Exception {
    return mvc.perform(
        post("/useradmin/consultants")
            .header(KEY_HEADER, key)
            .contentType("application/json")
            .accept("application/hal+json")
            .content(
                "{\"username\":\"tracked-counsellor\",\"email\":\"tracked-counsellor@example.test\",\"password\":\"SyntheticPassword1!\",\"firstname\":\"Tracked\",\"lastname\":\"Receipt\",\"formalLanguage\":true,\"absent\":false,\"tenantId\":7,\"agencyIds\":[],\"topicIds\":[],\"isGroupchatConsultant\":false}"));
  }

  @Test
  void aTrackedSuccessfulCreationReturnsAnInspectableReceiptWithoutCaching() throws Exception {
    var response = create(UUID.randomUUID().toString()).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    assertNotNull(
        response.getHeader(RECEIPT_HEADER), "Tracked creation must retain its persisted receipt");
    assertEquals("no-store", response.getHeader("Cache-Control"));
  }

  @Test
  void theSameTrackedOperationDoesNotCreateASecondAccount() throws Exception {
    String key = UUID.randomUUID().toString();
    var first = create(key).andReturn().getResponse();
    var replay = create(key).andReturn().getResponse();
    assertEquals(200, first.getStatus());
    assertEquals(
        409, replay.getStatus(), "Replay observes the existing receipt before any new creation");
    assertNotNull(first.getHeader(RECEIPT_HEADER));
    assertEquals(first.getHeader(RECEIPT_HEADER), replay.getHeader(RECEIPT_HEADER));
    assertEquals(
        1, freshCreations.get(), "Replay must not invoke the controlled creation effect twice");
    verify(dependency(ConsultantAdminFacade.class), times(2)).createNewConsultant(any(), any());
  }

  @Test
  void anInvalidTrackedKeyNeverReachesCreation() throws Exception {
    assertEquals(400, create("not-a-uuid").andReturn().getResponse().getStatus());
    verifyNoInteractions(dependency(ConsultantAdminFacade.class));
  }
}
