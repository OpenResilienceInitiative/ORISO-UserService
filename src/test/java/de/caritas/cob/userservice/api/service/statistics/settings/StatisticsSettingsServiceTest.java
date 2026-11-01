package de.caritas.cob.userservice.api.service.statistics.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatisticsSettingsServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-01T09:30:00Z"), ZoneOffset.UTC);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 9, 30);

  @Mock private StatisticsSettingChangeRepository repository;

  private AuthenticatedUser authenticatedUser;
  private StatisticsSettingsService service;

  @BeforeEach
  void setUp() {
    authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId("platform-admin-id");
    authenticatedUser.setUsername("platform.admin");
    authenticatedUser.setAccessToken("token");
    givenPlatformAdmin();
    when(repository.findFirstBySettingKeyOrderByIdDesc(anyString())).thenReturn(Optional.empty());
    when(repository.count()).thenReturn(0L);
    service = new StatisticsSettingsService(repository, authenticatedUser, CLOCK);
  }

  private void givenPlatformAdmin() {
    authenticatedUser.setTenantId(0L);
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
  }

  private void givenTenantAdmin() {
    authenticatedUser.setTenantId(5L);
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
  }

  private static StatisticsSettingChange change(
      long id, String key, String oldValue, String newValue, LocalDateTime when) {
    return StatisticsSettingChange.builder()
        .id(id)
        .settingKey(key)
        .oldValue(oldValue)
        .newValue(newValue)
        .changedById("someone")
        .changedByName("some.admin")
        .effectiveFrom(when)
        .build();
  }

  @Test
  void getSettings_Should_ReturnEveryDefault_When_NothingWasRecorded() {
    var settings = service.getSettings();

    assertThat(settings.settings())
        .extracting(StatisticsSettingsService.SettingState::key)
        .containsExactly(
            "agencyMinimumCounsellors",
            "tenantMinimumCounsellors",
            "platformMinimumCounsellors",
            "messageCountTracking",
            "requestChartWeeks",
            "topicMonths");
    var agency = settings.settings().get(0);
    assertThat(agency.value()).isEqualTo("5");
    assertThat(agency.options()).containsExactly("2", "3", "4", "5", "6", "7", "8", "9", "10");
    assertThat(agency.effectiveFrom()).isNull();
    assertThat(settings.settings().get(3).value()).isEqualTo("ENABLED");
    assertThat(settings.settings().get(3).options()).containsExactly("ENABLED", "DISABLED");
    assertThat(settings.settings().get(4).value()).isEqualTo("5");
    assertThat(settings.settings().get(4).options())
        .containsExactly("5", "8", "12", "16", "20", "26");
    assertThat(settings.settings().get(5).value()).isEqualTo("4");
    assertThat(settings.settings().get(5).options()).containsExactly("3", "4", "6", "12");
    assertThat(settings.revision()).isZero();
  }

  @Test
  void getSettings_Should_ReturnLatestValue_And_SinceWhenItApplies() {
    var since = LocalDateTime.of(2026, 10, 20, 8, 0);
    when(repository.findFirstBySettingKeyOrderByIdDesc("agencyMinimumCounsellors"))
        .thenReturn(Optional.of(change(7L, "agencyMinimumCounsellors", "5", "2", since)));
    when(repository.count()).thenReturn(7L);

    var settings = service.getSettings();

    var agency = settings.settings().get(0);
    assertThat(agency.value()).isEqualTo("2");
    assertThat(agency.effectiveFrom()).isEqualTo("2026-10-20T08:00");
    assertThat(agency.changedBy()).isEqualTo("some.admin");
    assertThat(settings.revision()).isEqualTo(7L);
  }

  @Test
  void getSettings_Should_ThrowForbidden_When_CallerIsNoPlatformAdmin() {
    givenTenantAdmin();

    assertThatThrownBy(() -> service.getSettings()).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void getHistory_Should_ThrowForbidden_When_CallerIsNoPlatformAdmin() {
    givenTenantAdmin();

    assertThatThrownBy(() -> service.getHistory()).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updateSettings_Should_ThrowForbidden_And_WriteNothing_When_CallerIsNoPlatformAdmin() {
    givenTenantAdmin();

    assertThatThrownBy(() -> service.updateSettings(Map.of("agencyMinimumCounsellors", "2"), 0L))
        .isInstanceOf(ForbiddenException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void updateSettings_Should_RecordOldValue_NewValue_Actor_And_Time() {
    service.updateSettings(
        Map.of("agencyMinimumCounsellors", "2", "tenantMinimumCounsellors", "5"), 0L);

    var captor = ArgumentCaptor.forClass(StatisticsSettingChange.class);
    // tenant threshold is unchanged (default 5) and must not produce a protocol line
    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getSettingKey()).isEqualTo("agencyMinimumCounsellors");
    assertThat(saved.getOldValue()).isEqualTo("5");
    assertThat(saved.getNewValue()).isEqualTo("2");
    assertThat(saved.getChangedById()).isEqualTo("platform-admin-id");
    assertThat(saved.getChangedByName()).isEqualTo("platform.admin");
    assertThat(saved.getEffectiveFrom()).isEqualTo(NOW);
  }

  @Test
  void updateSettings_Should_RejectValueOutsideTheOptions() {
    assertThatThrownBy(() -> service.updateSettings(Map.of("agencyMinimumCounsellors", "1"), 0L))
        .isInstanceOf(BadRequestException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void updateSettings_Should_RejectUnknownSetting() {
    assertThatThrownBy(() -> service.updateSettings(Map.of("chartWeeks", "8"), 0L))
        .isInstanceOf(BadRequestException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void updateSettings_Should_Conflict_When_SomeoneElseSavedInBetween() {
    when(repository.count()).thenReturn(3L);

    assertThatThrownBy(() -> service.updateSettings(Map.of("agencyMinimumCounsellors", "2"), 2L))
        .isInstanceOf(ConflictException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void getHistory_Should_ListEveryChangeOldestFirst() {
    when(repository.findAllByOrderByIdAsc())
        .thenReturn(
            List.of(
                change(1L, "agencyMinimumCounsellors", null, "5", NOW.minusDays(3)),
                change(2L, "agencyMinimumCounsellors", "5", "2", NOW)));

    var history = service.getHistory();

    assertThat(history)
        .extracting(StatisticsSettingsService.SettingChangeEntry::newValue)
        .containsExactly("5", "2");
    assertThat(history.get(1).oldValue()).isEqualTo("5");
    assertThat(history.get(1).changedByName()).isEqualTo("some.admin");
    assertThat(history.get(1).effectiveFrom()).isEqualTo("2026-11-01T09:30");
  }

  @Test
  void minimumCounsellors_Should_ReadTheConfiguredValue() {
    when(repository.findFirstBySettingKeyOrderByIdDesc("tenantMinimumCounsellors"))
        .thenReturn(Optional.of(change(9L, "tenantMinimumCounsellors", "5", "3", NOW)));

    assertThat(service.minimumCounsellors(StatisticsSetting.TENANT_MINIMUM_COUNSELLORS))
        .isEqualTo(3);
    assertThat(service.minimumCounsellors(StatisticsSetting.AGENCY_MINIMUM_COUNSELLORS))
        .isEqualTo(5);
  }

  @Test
  void minimumCounsellors_Should_FallBackToTheDefault_When_TheStoreFails() {
    when(repository.findFirstBySettingKeyOrderByIdDesc(anyString()))
        .thenThrow(new RuntimeException("db down"));

    assertThat(service.minimumCounsellors(StatisticsSetting.PLATFORM_MINIMUM_COUNSELLORS))
        .isEqualTo(5);
    assertThat(service.isMessageCountTrackingEnabled()).isTrue();
  }

  @Test
  void isMessageCountTrackingEnabled_Should_BeFalse_When_SwitchedOff() {
    when(repository.findFirstBySettingKeyOrderByIdDesc("messageCountTracking"))
        .thenReturn(Optional.of(change(4L, "messageCountTracking", "ENABLED", "DISABLED", NOW)));

    assertThat(service.isMessageCountTrackingEnabled()).isFalse();
  }

  @Test
  void updateSettings_Should_RejectAMissingRevision() {
    assertThatThrownBy(() -> service.updateSettings(Map.of("agencyMinimumCounsellors", "2"), null))
        .isInstanceOf(BadRequestException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void updateSettings_Should_RejectTimeWindowsOutsideTheBounds() {
    for (var invalid :
        List.of(
            Map.of("requestChartWeeks", "4"),
            Map.of("requestChartWeeks", "27"),
            Map.of("requestChartWeeks", "abc"),
            Map.of("topicMonths", "2"),
            Map.of("topicMonths", "13"),
            Map.of("topicMonths", "-1"))) {
      assertThatThrownBy(() -> service.updateSettings(invalid, 0L))
          .as("%s", invalid)
          .isInstanceOf(BadRequestException.class);
    }
    verify(repository, never()).save(any());
  }

  @Test
  void updateSettings_Should_RecordATimeWindowChangeInTheProtocol() {
    service.updateSettings(Map.of("requestChartWeeks", "12", "topicMonths", "4"), 0L);

    var captor = ArgumentCaptor.forClass(StatisticsSettingChange.class);
    // the topic window is unchanged (default 4) and must not produce a protocol line
    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getSettingKey()).isEqualTo("requestChartWeeks");
    assertThat(saved.getOldValue()).isEqualTo("5");
    assertThat(saved.getNewValue()).isEqualTo("12");
    assertThat(saved.getChangedByName()).isEqualTo("platform.admin");
    assertThat(saved.getEffectiveFrom()).isEqualTo(NOW);
  }

  @Test
  void timeWindows_Should_ReadTheConfiguredValues() {
    when(repository.findFirstBySettingKeyOrderByIdDesc("requestChartWeeks"))
        .thenReturn(Optional.of(change(10L, "requestChartWeeks", "5", "26", NOW)));
    when(repository.findFirstBySettingKeyOrderByIdDesc("topicMonths"))
        .thenReturn(Optional.of(change(11L, "topicMonths", "4", "12", NOW)));

    assertThat(service.chartWeeks()).isEqualTo(26);
    assertThat(service.topicMonths()).isEqualTo(12);
  }

  @Test
  void timeWindows_Should_FallBackToTodaysWindows_When_TheStoreFailsOrHoldsAnUnknownValue() {
    when(repository.findFirstBySettingKeyOrderByIdDesc("requestChartWeeks"))
        .thenReturn(Optional.of(change(10L, "requestChartWeeks", "5", "999", NOW)));
    when(repository.findFirstBySettingKeyOrderByIdDesc("topicMonths"))
        .thenThrow(new RuntimeException("db down"));

    assertThat(service.chartWeeks()).isEqualTo(5);
    assertThat(service.topicMonths()).isEqualTo(4);
  }
}
