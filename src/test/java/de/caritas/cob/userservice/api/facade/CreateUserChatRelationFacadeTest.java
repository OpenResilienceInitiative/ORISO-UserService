package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.service.UserAgencyService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.auditing.AuditingHandler;

@ExtendWith(MockitoExtension.class)
class CreateUserChatRelationFacadeTest {

  private static final long AGENCY_ID = 42L;

  @Mock private UserAgencyService userAgencyService;
  @Mock private RollbackFacade rollbackFacade;
  @Mock private AuditingHandler auditingHandler;
  @InjectMocks private CreateUserChatRelationFacade facade;

  private User user;
  private UserDTO userDto;

  @BeforeEach
  void setUp() {
    user = new User();
    user.setUserId("user-1");
    userDto = new UserDTO();
    userDto.setAgencyId(AGENCY_ID);
    userDto.setTermsAccepted("true");
    when(userAgencyService.getUserAgenciesByUser(user)).thenReturn(List.of());
  }

  @Test
  void createsTheUserAgencyRelationWithoutAChatProviderAccount() {
    facade.initializeUserChatAgencyRelation(userDto, user);

    var relation = ArgumentCaptor.forClass(UserAgency.class);
    verify(userAgencyService).saveUserAgency(relation.capture());
    verify(auditingHandler).markCreated(relation.getValue());
  }

  @Test
  void rejectsAnExistingUserAgencyRelation() {
    when(userAgencyService.getUserAgenciesByUser(user))
        .thenReturn(List.of(new UserAgency(user, AGENCY_ID)));

    assertThatThrownBy(() -> facade.initializeUserChatAgencyRelation(userDto, user))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void rollsBackTheNewAccountWhenTheRelationCannotBeSaved() {
    when(userAgencyService.saveUserAgency(any()))
        .thenThrow(new InternalServerErrorException("database unavailable"));

    assertThatThrownBy(() -> facade.initializeUserChatAgencyRelation(userDto, user))
        .isInstanceOf(InternalServerErrorException.class);

    verify(rollbackFacade).rollBackUserAccount(any());
  }
}
