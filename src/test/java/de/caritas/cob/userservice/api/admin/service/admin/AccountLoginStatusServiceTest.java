package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLoginStatusServiceTest {
  @Mock IdentityAccountStatusLookup lookup;

  @Test
  void oversizedPagesLimitDistinctReadsAndLeaveUnreadAccountsUnknown() {
    var ids = new java.util.ArrayList<String>();
    ids.add(null);
    ids.add(" ");
    for (int i = 0; i < 101; i++) {
      ids.add("account-" + i);
      ids.add("account-" + i);
    }
    when(lookup.findEnabledById(anyString())).thenReturn(Optional.of(true));

    var result = new AccountLoginStatusService(lookup, () -> 0L).activeByIds(ids);

    assertThat(result).hasSize(100).containsEntry("account-99", true);
    assertThat(result.get("account-100")).isNull();
    verify(lookup, times(100)).findEnabledById(anyString());
    verify(lookup, never()).findEnabledById("account-100");
  }

  @Test
  void elapsedPageBudgetStopsBeforeTheNextReadAndPreservesConfirmedStatus() {
    var elapsed = new java.util.concurrent.atomic.AtomicLong();
    when(lookup.findEnabledById("first"))
        .thenAnswer(
            invocation -> {
              elapsed.set(java.time.Duration.ofSeconds(2).toNanos());
              return Optional.of(false);
            });
    var service = new AccountLoginStatusService(lookup, elapsed::get);

    var result = service.activeByIds(java.util.List.of("first", "second"));

    assertThat(result).containsOnlyKeys("first").containsEntry("first", false);
    assertThat(result.get("second")).isNull();
    verify(lookup).findEnabledById("first");
    verifyNoMoreInteractions(lookup);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403})
  void doesNotHideInvalidOrUnauthorizedIdentityReads(int status) {
    var service = new AccountLoginStatusService(lookup);
    var failure = new WebApplicationException(status);
    when(lookup.findEnabledById("account")).thenThrow(failure);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.activeOf("account"))
        .isSameAs(failure);
  }

  @Test
  void retainsConfirmedRowsButStopsAfterALaterOutage() {
    when(lookup.findEnabledById("enabled")).thenReturn(Optional.of(true));
    when(lookup.findEnabledById("unavailable")).thenThrow(new ServiceUnavailableException());
    var status =
        new AccountLoginStatusService(lookup)
            .activeByIds(java.util.List.of("enabled", "unavailable", "remaining"));
    assertThat(status).containsOnlyKeys("enabled").containsEntry("enabled", true);
    verify(lookup).findEnabledById("enabled");
    verify(lookup).findEnabledById("unavailable");
    verifyNoMoreInteractions(lookup);
  }

  @Test
  void stopsRemainingPageReadsAfterProviderUnavailability() {
    when(lookup.findEnabledById("first")).thenThrow(new ProcessingException("timeout"));
    var status =
        new AccountLoginStatusService(lookup)
            .activeByIds(java.util.List.of("first", "second", "third"));
    assertThat(status).isEmpty();
    verify(lookup).findEnabledById("first");
    verifyNoMoreInteractions(lookup);
  }

  @Test
  void preservesConfirmedEnabledAndDisabledLogins() {
    var service = new AccountLoginStatusService(lookup);
    when(lookup.findEnabledById("enabled")).thenReturn(Optional.of(true));
    when(lookup.findEnabledById("disabled")).thenReturn(Optional.of(false));
    assertThat(service.activeOf("enabled")).isTrue();
    assertThat(service.activeOf("disabled")).isFalse();
  }

  @Test
  void missingIdentityIsUnknownNotDisabled() {
    when(lookup.findEnabledById("missing")).thenReturn(Optional.empty());
    assertThat(new AccountLoginStatusService(lookup).activeOf("missing")).isNull();
  }

  @Test
  void providerOutageDoesNotInventStatusOrFailTheList() {
    when(lookup.findEnabledById("unavailable")).thenThrow(new ServiceUnavailableException());
    when(lookup.findEnabledById("timeout")).thenThrow(new ProcessingException("timeout"));
    var service = new AccountLoginStatusService(lookup);
    assertThat(service.activeOf("unavailable")).isNull();
    assertThat(service.activeOf("timeout")).isNull();
  }

  @Test
  void missingRowIdDoesNotReadAnyIdentity() {
    var service = new AccountLoginStatusService(lookup);
    assertThat(service.activeOf(null)).isNull();
    assertThat(service.activeOf(" ")).isNull();
    verifyNoInteractions(lookup);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 503})
  void boundedHttpReadPreservesConfirmedRowsAndSeparatesOutageFromDeniedAuthority(int status) {
    when(lookup.findEnabledById("first")).thenReturn(Optional.of(false));
    var failure =
        status < 500
            ? org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.valueOf(status),
                "Fixture",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                null)
            : org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.valueOf(status),
                "Fixture",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                null);
    when(lookup.findEnabledById("second")).thenThrow(failure);
    var service = new AccountLoginStatusService(lookup);
    if (status < 500) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.activeByIds(java.util.List.of("first", "second", "third")))
          .isSameAs(failure);
    } else {
      assertThat(service.activeByIds(java.util.List.of("first", "second", "third")))
          .containsExactlyEntriesOf(java.util.Map.of("first", false));
    }
    verify(lookup, org.mockito.Mockito.never()).findEnabledById("third");
  }
}
