package de.caritas.cob.userservice.api.service.user;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AUTHENTICATED_USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.EMAIL;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.IS_LANGUAGE_FORMAL;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserMobileToken;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.auditing.AuditingHandler;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @InjectMocks private UserService userService;

  @Mock private UserRepository userRepository;

  @Mock private UserMobileTokenRepository userMobileTokenRepository;

  @Mock private UsernameTranscoder usernameTranscoder;

  @Mock
  @SuppressWarnings("unused")
  private AuditingHandler auditingHandler;

  @Test
  void createUser_Should_ReturnUser_When_RepositoryCallIsSuccessful() {
    when(userRepository.save(Mockito.any())).thenReturn(USER);

    var result = userService.createUser(USER_ID, USERNAME, EMAIL, IS_LANGUAGE_FORMAL);

    assertNotNull(result);
    assertEquals(USER, result);
  }

  @Test
  void createUser_Should_PersistCurrentTenant() {
    TenantContext.setCurrentTenant(83L);
    when(userRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              User persistedUser = invocation.getArgument(0);
              assertEquals(83L, persistedUser.getTenantId());
              return persistedUser;
            });

    try {
      var result = userService.createUser(USER_ID, USERNAME, EMAIL, IS_LANGUAGE_FORMAL);

      assertEquals(83L, result.getTenantId());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void getUser_Should_ReturnUser_WhenRepositoryCallIsSuccessful() {
    when(userRepository.findByUserIdAndDeleteDateIsNull(Mockito.anyString()))
        .thenReturn(Optional.of(USER));

    var result = userService.getUser(USER_ID);

    assertTrue(result.isPresent());
    assertEquals(USER, result.get());
  }

  @Test
  void findUsersByUsernameIncludingDeleted_ShouldQueryEncodedAndDecodedFormsWithoutDeleteFilter() {
    when(userRepository.findAllByUsernameInOrderByCreateDateAsc(any())).thenReturn(List.of(USER));

    var result = userService.findUsersByUsernameIncludingDeleted("marge.simpson@dreambau.de");

    assertEquals(List.of(USER), result);
    verify(userRepository)
        .findAllByUsernameInOrderByCreateDateAsc(
            List.of(
                new UsernameTranscoder().encodeUsername("marge.simpson@dreambau.de"),
                "marge.simpson@dreambau.de"));
  }

  @Test
  void getUserViaAuthenticatedUser_Should_returnOptionalEmpty_When_UserWasNotFound() {
    var viaAuthenticatedUser = userService.getUserViaAuthenticatedUser(AUTHENTICATED_USER);

    assertThat(viaAuthenticatedUser, is(Optional.empty()));
  }

  @Test
  void getUserViaAuthenticatedUser_Should_UserOptionalObject() {
    when(userRepository.findByUserIdAndDeleteDateIsNull(Mockito.any()))
        .thenReturn(Optional.of(USER));

    var result = userService.getUserViaAuthenticatedUser(AUTHENTICATED_USER);

    assertTrue(result.isPresent());
    assertEquals(USER, result.get());
  }

  @Test
  void saveUser_Should_UserObject() {
    when(userRepository.save(Mockito.any())).thenReturn(USER);

    var result = userService.saveUser(USER);

    assertNotNull(result);
    assertEquals(USER, result);
  }

  @Test
  void deleteUser_Should_CallDeleteUserRepository() {
    userService.deleteUser(USER);

    verify(userRepository, times(1)).delete(Mockito.any());
  }

  @Test
  void findUserByUsername_Should_SearchForEncodedAndDecodedUsername() {
    setField(userService, "usernameTranscoder", usernameTranscoder);
    when(usernameTranscoder.decodeUsername(any())).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(any())).thenReturn(USERNAME);

    userService.findUserByUsername(USERNAME);

    verify(usernameTranscoder, times(1)).encodeUsername(USERNAME);
    verify(usernameTranscoder, times(1)).decodeUsername(USERNAME);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void addMobileAppToken_Should_callNoOtherMethods_When_mobileTokenIsNullOrEmpty(String token) {
    this.userService.addMobileAppToken(null, token);

    verifyNoMoreInteractions(this.userMobileTokenRepository);
    verifyNoMoreInteractions(this.userRepository);
  }

  @Test
  void addMobileAppToken_Should_callNoOtherMethods_When_consultantDoesNotExist() {
    when(this.userRepository.findByUserIdAndDeleteDateIsNull(any())).thenReturn(Optional.empty());

    this.userService.addMobileAppToken("id", "token");

    verifyNoMoreInteractions(this.userMobileTokenRepository);
    verifyNoMoreInteractions(this.userRepository);
  }

  @Test
  void addMobileAppToken_Should_addMobileTokenToConsultant_When_consultantExists() {
    var user = new EasyRandom().nextObject(User.class);
    user.getUserMobileTokens().clear();
    when(this.userRepository.findByUserIdAndDeleteDateIsNull(any())).thenReturn(Optional.of(user));

    this.userService.addMobileAppToken("id", "token");

    verify(this.userMobileTokenRepository, times(1)).findByMobileAppToken("token");
    verify(this.userMobileTokenRepository, times(1)).save(any());
    assertThat(user.getUserMobileTokens(), hasSize(1));
  }

  @Test
  void addMobileAppToken_Should_throwConflictException_When_tokenAlreadyExists() {
    var user = new EasyRandom().nextObject(User.class);
    when(this.userRepository.findByUserIdAndDeleteDateIsNull(any())).thenReturn(Optional.of(user));
    when(this.userMobileTokenRepository.findByMobileAppToken(any()))
        .thenReturn(Optional.of(new UserMobileToken()));

    assertThrows(ConflictException.class, () -> this.userService.addMobileAppToken("id", "token"));
  }
}
