package de.caritas.cob.userservice.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.actions.session.PostConsultantDisplayNameChangedAliasMessageCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MessageClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Map;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AccountManagerTest {

  @InjectMocks AccountManager accountManager;

  @Mock ConsultantRepository consultantRepository;
  @Mock UserRepository userRepository;
  @Mock ConsultantAgencyRepository consultantAgencyRepository;
  @Mock UserServiceMapper userServiceMapper;
  @Mock AgencyService agencyService;
  @Mock MessageClient messageClient;
  @Mock SessionRepository sessionRepository;

  @Mock
  PostConsultantDisplayNameChangedAliasMessageCommand
      postConsultantDisplayNameChangedAliasMessageCommand;

  @Mock Page<Consultant.ConsultantBase> page;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void findConsultantsByInfix_Should_NotFilterByAgenciesIfAgencyListIsEmpty() {
    // given
    when(consultantRepository.findAllByInfix(eq("infix"), any(PageRequest.class))).thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", false, Lists.newArrayList(), 1, 10, "email", true);

    // then
    verify(consultantRepository).findAllByInfix(eq("infix"), any(PageRequest.class));
  }

  @Test
  void findConsultantsByInfix_Should_FilterByAgenciesIfAgencyListIsNotEmpty() {
    // given
    when(consultantRepository.findAllByInfixAndAgencyIds(
            eq("infix"), anyCollection(), any(PageRequest.class)))
        .thenReturn(page);

    // when
    accountManager.findConsultantsByInfix(
        "infix", true, Lists.newArrayList(1L), 1, 10, "email", true);

    // then
    verify(consultantRepository)
        .findAllByInfixAndAgencyIds(
            eq("infix"), eq(Lists.newArrayList(1L)), any(PageRequest.class));
  }

  @Test
  void patchUser_Should_executeDisplayNameChangedCommand_When_displayNameIsInPatchMap() {
    var consultant = easyRandom.nextObject(Consultant.class);
    var patchMap = Map.<String, Object>of("id", consultant.getId(), "displayName", "New Name");
    when(userRepository.findByUserIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    when(userServiceMapper.consultantOf(consultant, patchMap)).thenReturn(consultant);
    when(consultantRepository.save(consultant)).thenReturn(consultant);
    when(userServiceMapper.displayNameOf(patchMap)).thenReturn(Optional.of("New Name"));
    when(messageClient.updateUser(consultant.getRocketChatId(), "New Name")).thenReturn(true);
    when(userServiceMapper.mapOf(any(Consultant.class), any())).thenReturn(Map.of());

    accountManager.patchUser(patchMap);

    verify(messageClient).updateUser(consultant.getRocketChatId(), "New Name");
    verify(postConsultantDisplayNameChangedAliasMessageCommand).execute(consultant);
  }

  @Test
  void patchUser_Should_notExecuteDisplayNameChangedCommand_When_updateUserFails() {
    var consultant = easyRandom.nextObject(Consultant.class);
    var patchMap = Map.<String, Object>of("id", consultant.getId(), "displayName", "New Name");
    when(userRepository.findByUserIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    when(userServiceMapper.consultantOf(consultant, patchMap)).thenReturn(consultant);
    when(consultantRepository.save(consultant)).thenReturn(consultant);
    when(userServiceMapper.displayNameOf(patchMap)).thenReturn(Optional.of("New Name"));
    when(messageClient.updateUser(consultant.getRocketChatId(), "New Name")).thenReturn(false);
    when(userServiceMapper.mapOf(any(Consultant.class), any())).thenReturn(Map.of());

    accountManager.patchUser(patchMap);

    verify(messageClient).updateUser(consultant.getRocketChatId(), "New Name");
    verifyNoInteractions(postConsultantDisplayNameChangedAliasMessageCommand);
  }

  @Test
  void patchUser_Should_notExecuteDisplayNameChangedCommand_When_displayNameIsNotInPatchMap() {
    var consultant = easyRandom.nextObject(Consultant.class);
    var patchMap = Map.<String, Object>of("id", consultant.getId());
    when(userRepository.findByUserIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByIdAndDeleteDateIsNull(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    when(userServiceMapper.consultantOf(consultant, patchMap)).thenReturn(consultant);
    when(consultantRepository.save(consultant)).thenReturn(consultant);
    when(userServiceMapper.displayNameOf(patchMap)).thenReturn(Optional.empty());
    when(userServiceMapper.mapOf(any(Consultant.class), any())).thenReturn(Map.of());

    accountManager.patchUser(patchMap);

    verify(messageClient, never()).updateUser(anyString(), anyString());
    verifyNoInteractions(postConsultantDisplayNameChangedAliasMessageCommand);
  }
}
