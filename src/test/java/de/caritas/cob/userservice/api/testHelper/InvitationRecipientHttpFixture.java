package de.caritas.cob.userservice.api.testHelper;

import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakInvitationRecipientAvailability;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailability.Human;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ProtectedInvitationProfiles;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Controlled HTTP peer for the real adapter; its synthetic broker conveys no native authority. */
public final class InvitationRecipientHttpFixture {
  private enum Variants {
    BASIC,
    CHAT_ONLY,
    BOTH
  }

  public final java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>
      lastBody = new java.util.concurrent.atomic.AtomicReference<>();
  public final RestTemplate http = new RestTemplate();
  public final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  public final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  public final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  public final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  public final ReviewedMaintenanceProfiles reviewed = mock(ReviewedMaintenanceProfiles.class);
  public final ProtectedInvitationProfiles initial =
      new ProtectedInvitationProfiles(
          "realm-id",
          Map.of(
              AccountInviteTargetRole.TENANT_ADMIN, "tenant-admin",
              AccountInviteTargetRole.PLATFORM_ADMIN, "platform-admin"));
  public final KeycloakInvitationRecipientAvailability adapter;

  public InvitationRecipientHttpFixture(Supplier<Human> human) {
    this(human, true, Variants.BOTH);
  }

  public InvitationRecipientHttpFixture(Supplier<Human> human, boolean includeCompound) {
    this(human, includeCompound, Variants.BOTH);
  }

  public static InvitationRecipientHttpFixture withoutGroupChat(Supplier<Human> human) {
    return new InvitationRecipientHttpFixture(human, true, Variants.BASIC);
  }

  public static InvitationRecipientHttpFixture chatOnly(Supplier<Human> human) {
    return new InvitationRecipientHttpFixture(human, true, Variants.CHAT_ONLY);
  }

  private InvitationRecipientHttpFixture(
      Supplier<Human> human, boolean includeCompound, Variants variants) {
    http.getInterceptors()
        .add(
            (request, body, execution) -> {
              lastBody.set(
                  codec.read(
                      new String(body, java.nio.charset.StandardCharsets.UTF_8),
                      com.fasterxml.jackson.databind.JsonNode.class));
              return execution.execute(request, body);
            });
    var actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN,
            "account-admin",
            "synthetic",
            "a4444444-4444-4444-8444-444444444444");
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var token = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(token.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(token.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(token.accessToken()).thenReturn("synthetic-admin-token");
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(token);
    // Full startup retains all six legitimate ordinary unions. Chat-only remains a separate
    // valid installation, not a claimed failure of the pinned full startup catalogue.
    var consultants = new ArrayList<ReviewedMaintenanceProfiles.Profile>();
    if (variants != Variants.CHAT_ONLY)
      consultants.add(profile("ORDINARY", "consultant", Set.of("consultant")));
    if (variants != Variants.BASIC)
      consultants.add(
          profile("ORDINARY", "consultant-chat", Set.of("consultant", "group-chat-consultant")));
    var agency = new ArrayList<ReviewedMaintenanceProfiles.Profile>();
    agency.add(
        profile("ORDINARY", "agency-admin", Set.of("restricted-agency-admin", "user-admin")));
    if (includeCompound && variants != Variants.CHAT_ONLY)
      agency.add(
          profile(
              "ORDINARY",
              "combined-basic",
              Set.of("consultant", "restricted-agency-admin", "user-admin")));
    if (includeCompound && variants != Variants.BASIC)
      agency.add(
          profile(
              "ORDINARY",
              "combined",
              Set.of(
                  "consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")));
    when(reviewed.profiles())
        .thenReturn(
            Map.of(
                "USER",
                List.of(profile("ORDINARY", "user", Set.of("user"))),
                "CONSULTANT",
                List.copyOf(consultants),
                "AGENCY_ADMIN",
                List.copyOf(agency),
                "TENANT_ADMIN",
                List.of(profile("PROTECTED", "tenant-admin", Set.of("tenant-admin", "user-admin"))),
                "PLATFORM_ADMIN",
                List.of(profile("PROTECTED", "platform-admin", Set.of("platform-admin")))));
    adapter = newAdapter(human);
  }

  public KeycloakInvitationRecipientAvailability newAdapter(Supplier<Human> human) {
    return new KeycloakInvitationRecipientAvailability(
        http,
        actors,
        broker,
        codec,
        "http://identity.example",
        "oriso",
        "realm-id",
        human,
        reviewed,
        initial);
  }

  private static ReviewedMaintenanceProfiles.Profile profile(
      String family, String id, Set<String> roles) {
    return new ReviewedMaintenanceProfiles.Profile(family, id, 1, roles);
  }
}
