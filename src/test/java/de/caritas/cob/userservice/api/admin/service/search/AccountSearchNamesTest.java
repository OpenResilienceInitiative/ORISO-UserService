package de.caritas.cob.userservice.api.admin.service.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

class AccountSearchNamesTest {
  AgencyService agencies = mock(AgencyService.class);
  TenantService tenants = mock(TenantService.class);
  AccountSearchNames names = new AccountSearchNames(agencies, tenants);

  @Test
  void batchesOnlyEligibleDistinctIdsAndMatchesAccentsPerWord() {
    var ids = LongStream.rangeClosed(1, 205).boxed().collect(Collectors.toSet());
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call -> {
              List<Long> batch = call.getArgument(0);
              assertThat(batch).hasSizeLessThanOrEqualTo(100);
              return batch.stream()
                  .map(id -> new AgencyDTO().id(id).name("Cáritas Müller"))
                  .toList();
            });
    when(tenants.getRestrictedTenantData(Set.of(7L)))
        .thenReturn(
            List.of(
                new RestrictedTenantDTO().id(7L).name("Träger Cáritas"),
                new RestrictedTenantDTO().id(999L).name("Caritas")));
    var result = names.resolve(AdminSearchQuery.parse("caritas muller"), ids, Set.of(7L));
    assertThat(result.get("caritas").agencies()).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(result.get("caritas").tenants()).containsExactly(7L);
    assertThat(result.get("muller").tenants()).isEmpty();
    verify(agencies, times(3)).getAgenciesWithoutCaching(anyList());
  }

  @Test
  void blankSkipsOwnerCalls() {
    assertThat(names.resolve(AdminSearchQuery.parse("*"), Set.of(1L), Set.of(2L))).isEmpty();
    verifyNoInteractions(agencies, tenants);
  }

  @Test
  void transportFailureCannotBecomeIncompleteSuccess() {
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenThrow(new ResourceAccessException("offline"));
    assertThatThrownBy(() -> names.resolve(AdminSearchQuery.parse("caritas"), Set.of(1L), Set.of()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(502));
  }
}
