package de.caritas.cob.userservice.testutils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.service.matrix.CounsellorStartedCall;

/** Test doubles for the rule that only counsellors start calls (Frank, 17 Nov 2026). */
public final class CallStartRules {
  private CallStartRules() {}

  /**
   * Every call counts as a running counsellor call: for tests about another rule of the call
   * policy, which an asker's request must reach.
   */
  public static CounsellorStartedCall everyCallRunsForACounsellor() {
    var calls = mock(CounsellorStartedCall.class);
    lenient().when(calls.isRunning(any(), any(), any())).thenReturn(true);
    return calls;
  }
}
