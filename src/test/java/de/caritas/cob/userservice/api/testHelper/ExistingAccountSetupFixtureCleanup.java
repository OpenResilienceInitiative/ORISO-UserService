package de.caritas.cob.userservice.api.testHelper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes only rows created for one direct-created account in a non-transactional HTTP fixture. */
public final class ExistingAccountSetupFixtureCleanup {

  private ExistingAccountSetupFixtureCleanup() {}

  public static void admin(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, String identityId) {
    cleanup(jdbc, transactions, identityId, false);
  }

  public static void consultant(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, String identityId) {
    cleanup(jdbc, transactions, identityId, true);
  }

  private static void cleanup(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      String identityId,
      boolean consultant) {
    if (identityId == null) {
      return;
    }
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "DELETE FROM invite_email_delivery WHERE account_invite_id IN "
                      + "(SELECT id FROM account_invite WHERE purpose = 'EXISTING_ACCOUNT_SETUP'"
                      + " AND provisioned_user_id = ?)",
                  identityId);
              jdbc.update(
                  "DELETE FROM account_invite WHERE purpose = 'EXISTING_ACCOUNT_SETUP'"
                      + " AND provisioned_user_id = ?",
                  identityId);
              if (consultant) {
                jdbc.update("DELETE FROM consultant_agency WHERE consultant_id = ?", identityId);
                jdbc.update(
                    "DELETE FROM consultant_mobile_token WHERE consultant_id = ?", identityId);
                jdbc.update("DELETE FROM consultant_topic WHERE consultant_id = ?", identityId);
                jdbc.update("DELETE FROM language WHERE consultant_id = ?", identityId);
                jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", identityId);
              } else {
                jdbc.update("DELETE FROM admin_agency WHERE admin_id = ?", identityId);
                jdbc.update("DELETE FROM admin WHERE admin_id = ?", identityId);
              }
            });
  }
}
