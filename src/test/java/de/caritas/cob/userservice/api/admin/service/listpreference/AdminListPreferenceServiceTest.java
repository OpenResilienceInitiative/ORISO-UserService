package de.caritas.cob.userservice.api.admin.service.listpreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.listpreference.AdminListPreferenceService.ListSort;
import de.caritas.cob.userservice.api.model.AdminListPreference;
import de.caritas.cob.userservice.api.port.out.AdminListPreferenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AdminListPreferenceServiceTest {

  @Mock private AdminListPreferenceRepository repository;
  @InjectMocks private AdminListPreferenceService service;

  @Test
  void saveOwnSort_updatesTheRowAnotherDeviceJustCreated() {
    var createdMeanwhile =
        AdminListPreference.builder().id(7L).userId("u-1").tab("consultants").build();
    when(repository.findByUserIdAndTab("u-1", "consultants"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(createdMeanwhile));
    when(repository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveOwnSort("u-1", "consultants", new ListSort("LASTNAME", "DESC"));

    var saved = ArgumentCaptor.forClass(AdminListPreference.class);
    verify(repository, times(2)).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(7L);
    assertThat(saved.getValue().getSortField()).isEqualTo("LASTNAME");
    assertThat(saved.getValue().getSortOrder()).isEqualTo("DESC");
  }
}
