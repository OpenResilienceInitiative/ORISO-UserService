package de.caritas.cob.userservice.api.service.enquiry;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.EnquiryRejection;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** No transaction encloses the external verified protocol closure. */
@Service
@RequiredArgsConstructor
public class EnquiryRejectionService {
  private final @NonNull EnquiryRejectionTransactions transactions;
  private final @NonNull AgencyMatrixCredentialClient credentials;
  private final @NonNull MatrixSynapseService matrix;
  private final @NonNull MatrixRoomClient rooms;

  public boolean rejectEnquiry(Long id) {
    return close(transactions.begin(id));
  }

  public boolean repair(Long id) {
    return close(transactions.resume(id));
  }

  private boolean close(EnquiryRejectionTransactions.Attempt attempt) {
    var decision = attempt.decision();
    if (decision.getState() == EnquiryRejection.State.CONFIRMED) return true;
    // Only the winning claim may perform protocol traffic; retained claims are not fresh attempts.
    if (attempt.token() == null) return false;
    boolean verified = false;
    String stage = "CREDENTIALS";
    try {
      var credential = credentials.fetchMatrixCredentials(decision.getAgencyId()).orElse(null);
      if (credential != null
          && credential.getMatrixUserId() != null
          && !credential.getMatrixUserId().isBlank()) {
        var operator = credential.getMatrixUserId();
        var token = matrix.loginAsUserAccessToken(operator);
        stage = "PRIMARY";
        if (token != null
            && !token.isBlank()
            && decision.getPrimaryRoomId() != null
            && !decision.getPrimaryRoomId().isBlank()
            && rooms.closeRoomForMessagesVerified(decision.getPrimaryRoomId(), operator, token)) {
          stage = "TEAM";
          verified =
              decision.getTeamRoomId() == null
                  || rooms.closeRoomForMessagesVerified(decision.getTeamRoomId(), operator, token);
        }
      }
    } catch (RuntimeException failure) {
      /* bounded stage only; no raw Matrix/provider/credential logs */
    }
    return transactions.finish(
        decision.getSessionId(), decision.getGeneration(), attempt.token(), verified, stage);
  }
}
