package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.matrixrtc.CallMediaPolicy;
import de.caritas.cob.userservice.api.service.matrixrtc.MatrixRtcCallPolicyService;
import de.caritas.cob.userservice.api.service.matrixrtc.MatrixRtcPolicyTokenVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/matrixrtc")
@RequiredArgsConstructor
public class MatrixRtcCallPolicyController {

  static final String POLICY_TOKEN_HEADER = "x-matrixrtc-policy-token";

  private final @NonNull MatrixRtcCallPolicyService callPolicyService;
  private final @NonNull MatrixRtcPolicyTokenVerifier tokenVerifier;

  @PostMapping("/call-policy")
  public ResponseEntity<CallMediaPolicy> resolve(
      @RequestHeader(value = POLICY_TOKEN_HEADER, required = false) String policyToken,
      @Valid @RequestBody CallPolicyRequest request) {
    if (!tokenVerifier.isValid(policyToken)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return ResponseEntity.ok(
        callPolicyService.resolve(request.sourceRoomId(), request.matrixUserId()));
  }

  public record CallPolicyRequest(@NotBlank String sourceRoomId, @NotBlank String matrixUserId) {}
}
