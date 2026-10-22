package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/** Public controller with actual owned SQL; native permission and SMTP are separate gates. */
class UserSelfProtectedOtpScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void committedTenantAdminAppUsesOnlyItsCurrentOwnedProtectedOrigin() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(0, fixture.jdbc.queryForObject("select count(*) from user", Integer.class));
      verifyNoInteractions(fixture.nativeClient, fixture.accounts);
      fixture.server.verify();
    }
  }

  @Test
  void committedPlatformAdminPreservesNativeTenantZeroInSelfAppCommand() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(0L, "SUPER");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(
          0L,
          fixture.jdbc.queryForObject(
              "select tenant_id from identity_self_app_otp_intent", Long.class));
      fixture.server.verify();
    }
  }

  @Test
  void localAdminWithoutNativeAdoptionCannotAuthorizeOtpByItsOwnRow() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update("delete from protected_account_activation");
      refusedByNativeBeforeEnrollment(fixture);
    }
  }

  @Test
  void stagedLocalAdminCannotSubstituteForCurrentNativePermission() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update("update protected_account_activation set state='WAITING_OTP'");
      refusedByNativeBeforeEnrollment(fixture);
    }
  }

  @Test
  void localAcceptanceTupleCannotSubstituteForCurrentNativePermission() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "update protected_invitation_acceptance set account_id='99999999-9999-4999-8999-999999999999'");
      refusedByNativeBeforeEnrollment(fixture);
    }
  }

  @Test
  void currentAdminTenantDriftIsDeniedBeforeAnyMachineOrNativeCall() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update("update admin set tenant_id=8");
      refusedBeforeAnyEffect(fixture);
    }
  }

  @Test
  void legacyNullPlatformTenantIsNeverPromotedToNativeZero() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(0L, "SUPER");
      fixture.jdbc.update("update admin set tenant_id=null");
      refusedBeforeAnyEffect(fixture);
    }
  }

  @Test
  void collidingOrdinaryAndAdminRowsCannotChooseAnAuthority() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "insert into user values(?,7,null,'ordinary','original@oriso.org')", ACCOUNT);
      refusedBeforeAnyEffect(fixture);
    }
  }

  @Test
  void committedTenantAdminEmailUpdatesActualAdminOnlyAfterVerifiedReceipt() throws Exception {
    completedProtectedEmail(7L, "TENANT");
  }

  @Test
  void committedPlatformAdminEmailKeepsTenantZeroAndNeverNeedsAnOrdinaryUser() throws Exception {
    completedProtectedEmail(0L, "SUPER");
  }

  @Test
  void currentAgencyAdminEmailUpdatesActualAdminWithOrdinaryNativeSelfAuthority() throws Exception {
    completedProtectedEmail(7L, "AGENCY");
  }

  @Test
  void sharedAdminConsultantEmailCommitsBothLocalDomainsWithTheOwnedInvite() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','secondary@oriso.org')", ACCOUNT);
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, false, false);
      assertEquals(
          204,
          fixture.controller.finishTwoFactorAuthByEmailSetup("123456").getStatusCode().value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("pending@oriso.org", fixture.currentEmail());
      assertEquals(
          "pending@oriso.org",
          fixture.jdbc.queryForObject("select email from consultant", String.class));
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void concurrentAdminEmailChangeKeepsUnknownIntentAndDoesNotOverwriteNewerSql() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.prepareDeliveredEmail();
      String delivery = fixture.pendingEmailOperation();
      fixture.beforeEmailReceipt =
          () -> fixture.jdbc.update("update admin set email='newer@oriso.org'");
      fixture.expectEmailFinish(false, false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup("123456"));
      assertEquals("FROZEN", fixture.status());
      assertEquals("newer@oriso.org", fixture.currentEmail());
      assertEquals(delivery, fixture.pendingEmailOperation());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void failedOwnedInviteWriteRollsBackBothAdminEmailsAndKeepsTheFrozenReceiptPending()
      throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','secondary@oriso.org')", ACCOUNT);
      fixture.prepareDeliveredEmail();
      String delivery = fixture.pendingEmailOperation();
      fixture.jdbc.execute("alter table account_invite add constraint frozen_invite_write check(write_count=0)");
      fixture.expectEmailFinish(false, false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup("123456"));
      assertEquals("FROZEN", fixture.status());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals(
          "secondary@oriso.org",
          fixture.jdbc.queryForObject("select email from consultant", String.class));
      assertEquals(delivery, fixture.pendingEmailOperation());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void unknownSelfOperationCannotReplayUnderAChangedSqlDomainWithTheSameUuid() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.expectNative(true, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.reset();
      fixture.jdbc.update("delete from user");
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','original@oriso.org')", ACCOUNT);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(0, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void rejectedProtectedEmailCodeKeepsAdminEmailAndTheOriginalDeliveryPointer() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.prepareDeliveredEmail();
      String delivery = fixture.pendingEmailOperation();
      fixture.expectEmailFinish(false, true, false);
      assertEquals(
          400,
          fixture.controller.finishTwoFactorAuthByEmailSetup("123456").getStatusCode().value());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(delivery, fixture.pendingEmailOperation());
      assertEquals(0, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void tamperedProtectedEmailReceiptKeepsLocalAdminAndOwnedInviteUnchanged() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.prepareDeliveredEmail();
      fixture.tamperReceipt = true;
      fixture.expectEmailFinish(false, false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.finishTwoFactorAuthByEmailSetup("123456"));
      assertEquals("FROZEN", fixture.status());
      assertEquals("original@oriso.org", fixture.currentEmail());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  private static void completedProtectedEmail(long tenant, String type) throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(tenant, type);
      fixture.prepareDeliveredEmail();
      fixture.expectEmailFinish(false, false, false);
      assertEquals(
          204,
          assertDoesNotThrow(() -> fixture.controller.finishTwoFactorAuthByEmailSetup("123456"))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("pending@oriso.org", fixture.currentEmail());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertNull(fixture.pendingEmailOperation());
      assertEquals(0, fixture.jdbc.queryForObject("select count(*) from user", Integer.class));
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
      assertEquals(
          204,
          fixture.controller.finishTwoFactorAuthByEmailSetup("123456").getStatusCode().value());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void independentlyNativeAuthorizedAdoptedAdminNeedsNoDuplicateLocalAdoptionRegistry()
      throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update("delete from protected_account_activation");
      fixture.jdbc.update("delete from protected_invitation_acceptance");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void currentConsultantUsesItsOwnSqlDomainForOrdinarySelfOtp() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.jdbc.update("delete from user");
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','original@oriso.org')", ACCOUNT);
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void currentAgencyAdminKeepsOrdinarySelfAuthorityForOtp() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "AGENCY");
      fixture.protectedAccount = false;
      fixture.jdbc.update("update account_invite set target_role='AGENCY_ADMIN'");
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  @Test
  void collidingUserAndConsultantRowsCannotChooseTheirSelfDomain() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','original@oriso.org')", ACCOUNT);
      refusedBeforeAnyEffect(fixture);
    }
  }

  @Test
  void foreignConsultantAndAdminRowsCannotChooseAnAuthority() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "insert into consultant values(?,8,null,'ordinary','original@oriso.org')", ACCOUNT);
      refusedBeforeAnyEffect(fixture);
    }
  }

  @Test
  void sameTenantAdminAndConsultantKeepsItsExplicitNativeProtectedProfile() throws Exception {
    try (var fixture = new UserSelfAppOtpScenarioCBoundaryTest.Fixture(true, true)) {
      fixture.protectedAdmin(7L, "TENANT");
      fixture.jdbc.update(
          "insert into consultant values(?,7,null,'ordinary','original@oriso.org')", ACCOUNT);
      fixture.expectNative(false, false);
      assertEquals(
          200,
          assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()))
              .getStatusCode()
              .value());
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      verifyNoInteractions(fixture.accounts, fixture.nativeClient);
      fixture.server.verify();
    }
  }

  private static void refusedByNativeBeforeEnrollment(
      UserSelfAppOtpScenarioCBoundaryTest.Fixture fixture) {
    fixture.refuseNativeRead();
    assertThrows(
        ServiceUnavailableException.class,
        () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
    assertEquals("PREPARED", fixture.status());
    assertEquals("PENDING_SETUP", fixture.inviteStatus());
    assertEquals(0, fixture.domainWrites());
    verifyNoInteractions(fixture.accounts, fixture.nativeClient);
    fixture.server.verify();
  }

  private static void refusedBeforeAnyEffect(UserSelfAppOtpScenarioCBoundaryTest.Fixture fixture) {
    assertThrows(
        ForbiddenException.class,
        () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
    assertNull(fixture.status());
    assertEquals("PENDING_SETUP", fixture.inviteStatus());
    assertEquals(0, fixture.domainWrites());
    verifyNoInteractions(fixture.nativeClient, fixture.accounts);
    fixture.server.verify();
  }
}
