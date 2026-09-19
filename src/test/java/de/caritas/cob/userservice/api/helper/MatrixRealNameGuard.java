package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * ADR-002 §2 regression guard for ORISO-UserService#1200.
 *
 * <p>A counsellor's Matrix {@code displayname} is readable by every member of a shared room via
 * {@code /joined_members} — including the advice seeker. So no call to the Matrix client may ever
 * carry {@code firstName + " " + lastName}. Asserting on the whole invocation log rather than on
 * one expected argument means a NEW Matrix call added next to a fixed one cannot reintroduce the
 * leak unnoticed.
 */
public final class MatrixRealNameGuard {

  private MatrixRealNameGuard() {}

  /**
   * @param matrixClientMock a Mockito mock/spy of {@link
   *     de.caritas.cob.userservice.api.port.out.MatrixUserClient} (or an implementation of it)
   * @param firstName the counsellor's real first name
   * @param lastName the counsellor's real last name
   */
  public static void assertNoRealNameReachedMatrix(
      Object matrixClientMock, String firstName, String lastName) {
    var realName = firstName + " " + lastName;

    var offendingCalls =
        Mockito.mockingDetails(matrixClientMock).getInvocations().stream()
            .filter(
                invocation -> Arrays.stream(invocation.getArguments()).anyMatch(realName::equals))
            .map(Invocation::toString)
            .toList();

    assertThat(offendingCalls)
        .as(
            "Matrix calls carrying the counsellor's real name '%s' (ADR-002 §2, issue #1200)",
            realName)
        .isEmpty();
  }
}
