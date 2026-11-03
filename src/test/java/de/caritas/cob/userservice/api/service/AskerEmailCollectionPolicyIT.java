package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real repository proof; fixture mutations roll back, including archived context/member rows. */
@SpringBootTest
@ActiveProfiles("testing")
@Transactional
class AskerEmailCollectionPolicyIT {
  @Autowired AskerEmailCollectionPolicy policy;
  @Autowired SessionRepository sessions;
  @Autowired UserRepository users;
  @Autowired UserChatRepository memberships;
  @Autowired ChatRepository chats;
  @Autowired de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;
  @MockitoBean TenantService tenants;

  private de.caritas.cob.userservice.api.model.User user;
  private Settings settings;

  @BeforeEach
  void actualOwner() {
    user = sessions.findById(1216L).orElseThrow().getUser();
    user.setTenantId(42L);
    users.save(user);
    sessions
        .findByUser(user)
        .forEach(
            session -> {
              session.setConversationType(ConversationType.INTERNAL_GROUP);
              sessions.save(session);
            });
    memberships.deleteAll(memberships.findByUser(user));
    settings =
        new Settings()
            .featureAskerEmailEnabled(true)
            .featureAskerEmailCollectionAgencyCounsellingEnabled(true)
            .featureAskerEmailCollectionSelfHelpEnabled(false);
    when(tenants.getRestrictedTenantDataFresh(42L))
        .thenReturn(new RestrictedTenantDTO().id(42L).settings(settings));
  }

  @Test
  void archivedOwnedPersonalSessionStillSuppliesItsActualTypeAndEveryActionRechecksIt() {
    assertThat(policy.allowedForAccount(user)).isFalse();
    var owned = sessions.findById(1216L).orElseThrow();
    owned.setTenantId(42L);
    owned.setConversationType(ConversationType.AGENCY_COUNSELLING);
    owned.setStatus(Session.SessionStatus.DONE);
    sessions.save(owned);
    assertThat(policy.allowedForAccount(user)).isTrue();
    settings.setFeatureAskerEmailCollectionAgencyCounsellingEnabled(false);
    assertThat(policy.allowedForAccount(user)).isFalse();
    settings.setFeatureAskerEmailCollectionAgencyCounsellingEnabled(true);
    assertThat(policy.allowedForAccount(user)).isTrue();
    owned.setConversationType(ConversationType.LIVE_CHAT);
    sessions.save(owned);
    assertThat(policy.allowedForAccount(user)).isFalse();
  }

  @Test
  void onlyJoinedSelfHelpRowSuppliesGroupContextAndMembershipRemovalRevokesIt() {
    settings.setFeatureAskerEmailCollectionSelfHelpEnabled(true);
    var group =
        chats.save(
            de.caritas.cob.userservice.api.model.Chat.builder()
                .topic("collection policy fixture")
                .consultingTypeId(1)
                .conversationType(ConversationType.SELF_HELP)
                .initialStartDate(java.time.LocalDateTime.now())
                .startDate(java.time.LocalDateTime.now())
                .duration(60)
                .chatOwner(consultants.findAll().getFirst())
                .build());
    assertThat(policy.allowedForAccount(user)).isFalse();
    var membership = memberships.save(UserChat.builder().user(user).chat(group).build());
    assertThat(policy.allowedForAccount(user)).isTrue();
    group.setConversationType(ConversationType.INTERNAL_GROUP);
    chats.save(group);
    assertThat(policy.allowedForAccount(user)).isFalse();
    group.setConversationType(ConversationType.SELF_HELP);
    chats.save(group);
    assertThat(policy.allowedForAccount(user)).isTrue();
    memberships.delete(membership);
    assertThat(policy.allowedForAccount(user)).isFalse();
  }
}
