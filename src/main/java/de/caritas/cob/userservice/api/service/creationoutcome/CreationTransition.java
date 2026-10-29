package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;

public sealed interface CreationTransition {
  record Workflow(WorkflowState state, Reason reason) implements CreationTransition {}

  record Row(RowState state, Reason reason) implements CreationTransition {}

  record Onboarding(OnboardingState state, Reason reason) implements CreationTransition {}

  record Compensation(CompensationState state, Reason reason) implements CreationTransition {}
}
