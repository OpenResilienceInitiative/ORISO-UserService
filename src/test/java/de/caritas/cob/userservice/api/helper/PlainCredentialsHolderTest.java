package de.caritas.cob.userservice.api.helper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** US-C01: PlainCredentialsHolder must not leak registration passwords across requests. */
class PlainCredentialsHolderTest {

  private static final String REGISTRATION_PASSWORD = "TestPw123!";

  @AfterEach
  void tearDown() {
    PlainCredentialsHolder.clear();
  }

  @Test
  @DisplayName("US-C01 Smoke 3: clear removes credentials from ThreadLocal")
  void clear_Should_RemoveCredentialsFromThreadLocal() {
    PlainCredentialsHolder.set("user", REGISTRATION_PASSWORD);

    PlainCredentialsHolder.clear();

    assertThat(PlainCredentialsHolder.get(), nullValue());
  }

  @Test
  void get_Should_ReturnStoredCredentialsUntilCleared() {
    PlainCredentialsHolder.set("user", REGISTRATION_PASSWORD);

    PlainCredentialsHolder.PlainCredentials credentials = PlainCredentialsHolder.get();

    assertThat(credentials.getUsername(), is("user"));
    assertThat(credentials.getPassword(), is(REGISTRATION_PASSWORD));
  }

  @Test
  @DisplayName("US-C01: simulates next request on same thread after registration cleanup")
  void nextRequestOnSameThread_ShouldNotSeePreviousPassword() {
    PlainCredentialsHolder.set("registered-user", REGISTRATION_PASSWORD);
    PlainCredentialsHolder.clear();

    PlainCredentialsHolder.set("next-user", null);

    assertThat(PlainCredentialsHolder.get().getPassword(), nullValue());
    assertThat(PlainCredentialsHolder.get().getPassword(), not(REGISTRATION_PASSWORD));
  }
}
