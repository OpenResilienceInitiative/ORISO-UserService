package de.caritas.cob.userservice.api.admin.service.asker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerFilter;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Tenant;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.AskerSearchRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository.UserAgencyId;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AskerAdminSearchServiceTest {

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @Mock private AskerSearchRepository askerSearchRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;
  @Mock private AdminScope adminScope;

  private AskerAdminSearchService service;

  @BeforeEach
  void setUp() {
    service =
        new AskerAdminSearchService(
            askerSearchRepository,
            sessionRepository,
            userAgencyRepository,
            adminScope,
            usernameTranscoder);
  }

  @Test
  void findAskers_Should_mapDecodedUsernameAgenciesAndDeletionState() {
    givenReach(new Platform());
    var active = asker("a-1", "anna", 7L, null, DeletionLifecycleState.ACTIVE);
    var marked =
        asker(
            "a-2",
            "bert",
            7L,
            LocalDateTime.of(2026, 11, 12, 22, 0),
            DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    givenPage(List.of(active, marked), 12);
    when(sessionRepository.findAgencyIdsByUserIds(anyCollection()))
        .thenReturn(List.of(agencyOf("a-1", 30L), agencyOf("a-1", 10L), agencyOf("a-2", 10L)));
    when(userAgencyRepository.findByUserUserIdIn(anyCollection()))
        .thenReturn(List.of(membership(active, 10L), membership(active, 20L)));

    var result = service.findAskers(1, 2, new AskerFilter());

    assertThat(result.getTotal()).isEqualTo(12L);
    var first = result.getEmbedded().get(0);
    assertThat(first.getId()).isEqualTo("a-1");
    assertThat(first.getUsername()).isEqualTo("anna");
    assertThat(first.getTenantId()).isEqualTo(7L);
    assertThat(first.getAgencyIds()).containsExactly(10L, 20L, 30L);
    assertThat(first.getCreateDate()).isEqualTo("2026-11-01T08:30:00");
    assertThat(first.getDeleteDate()).isNull();
    assertThat(first.getDeletionState()).isEqualTo("ACTIVE");
    var second = result.getEmbedded().get(1);
    assertThat(second.getAgencyIds()).containsExactly(10L);
    assertThat(second.getDeleteDate()).isEqualTo("2026-11-12T22:00:00");
    assertThat(second.getDeletionState()).isEqualTo("READ_ONLY_SAFEGUARD");
  }

  @Test
  void findAskers_Should_reportMarkedLegacyRowWithoutStateAsMarked() {
    givenReach(new Platform());
    givenPage(List.of(asker("a-3", "carl", 7L, LocalDateTime.of(2026, 11, 1, 0, 0), null)), 1);

    var result = service.findAskers(1, 20, null);

    assertThat(result.getEmbedded().get(0).getDeletionState())
        .isEqualTo(AskerAdminSearchService.MARKED_FOR_DELETION);
  }

  @Test
  void findAskers_Should_limitPageSizeAndStartAtFirstPage() {
    givenReach(new Platform());
    givenPage(List.of(), 0);

    service.findAskers(0, 5000, null);

    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(askerSearchRepository).findAll(any(Specification.class), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isZero();
    assertThat(pageable.getValue().getPageSize()).isEqualTo(AskerAdminSearchService.MAX_PER_PAGE);
    assertThat(pageable.getValue().getSort().getOrderFor("createDate").isDescending()).isTrue();
  }

  @Test
  void effectiveTenantId_Should_honourRequestedTenant_When_callerIsPlatformAdmin() {
    var filter = new AskerFilter().tenantId(9L);

    assertThat(AskerAdminSearchService.effectiveTenantId(new Platform(), filter)).isEqualTo(9L);
  }

  @Test
  void effectiveTenantId_Should_keepCallersOwnTenant_When_callerIsTenantOrAgencyAdmin() {
    var filter = new AskerFilter().tenantId(9L);

    assertThat(AskerAdminSearchService.effectiveTenantId(new Tenant(3L), filter)).isEqualTo(3L);
    assertThat(AskerAdminSearchService.effectiveTenantId(new Agencies(3L, Set.of(1L)), filter))
        .isEqualTo(3L);
  }

  @Test
  void usernameVariants_Should_matchPlainAndEncodedStorage() {
    var encoded = usernameTranscoder.encodeUsername("anna");

    assertThat(service.usernameVariants("  anna ")).containsExactlyInAnyOrder("anna", encoded);
    assertThat(service.usernameVariants(encoded)).containsExactlyInAnyOrder("anna", encoded);
    assertThat(service.usernameVariants(" ")).isEmpty();
    assertThat(service.usernameVariants(null)).isEmpty();
  }

  private void givenReach(AdminScope.Reach reach) {
    when(adminScope.current()).thenReturn(reach);
    when(adminScope.narrow(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @SuppressWarnings("unchecked")
  private void givenPage(List<User> users, long total) {
    when(askerSearchRepository.findAll(any(Specification.class), any(Pageable.class)))
        .thenAnswer(
            invocation -> new PageImpl<>(users, (Pageable) invocation.getArgument(1), total));
  }

  private User asker(
      String id,
      String plainUsername,
      Long tenantId,
      LocalDateTime deleteDate,
      DeletionLifecycleState state) {
    var user = new User();
    user.setUserId(id);
    user.setUsername(usernameTranscoder.encodeUsername(plainUsername));
    user.setTenantId(tenantId);
    user.setCreateDate(LocalDateTime.of(2026, 11, 1, 8, 30));
    user.setDeleteDate(deleteDate);
    user.setDeletionLifecycleState(state);
    return user;
  }

  private static UserAgencyId agencyOf(String userId, Long agencyId) {
    return new UserAgencyId() {
      @Override
      public String getUserId() {
        return userId;
      }

      @Override
      public Long getAgencyId() {
        return agencyId;
      }
    };
  }

  private static UserAgency membership(User user, Long agencyId) {
    var membership = new UserAgency();
    membership.setUser(user);
    membership.setAgencyId(agencyId);
    return membership;
  }
}
