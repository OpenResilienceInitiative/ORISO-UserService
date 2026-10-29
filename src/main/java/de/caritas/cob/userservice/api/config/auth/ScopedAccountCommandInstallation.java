package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ClaimedPasswordMaintenance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryRegistrationLifecycle;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ScopedAskerReactivation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfContactEmailMaintenance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.ScopedUsernameAvailability;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.SelfLocaleMaintenance;
import de.caritas.cob.userservice.api.adapters.web.controller.UserController;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyDeletionValidationService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountRoleAddition;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyAdminInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.ProtectedInvitationIssuance;
import de.caritas.cob.userservice.api.service.accountpolicy.CheckedAccountPolicyReads;
import de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.DefaultAccountInactivityEffects;
import de.caritas.cob.userservice.api.workflow.delete.scheduler.DeleteUserAccountScheduler;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Fixed read-only inspection of the installed production consumers after singleton wiring. */
final class ScopedAccountCommandInstallation {
  private final ApplicationContext context;

  ScopedAccountCommandInstallation(ApplicationContext context) {
    this.context = java.util.Objects.requireNonNull(context);
  }

  void verify() {
    require(
        context.getBean(DeleteUserAccountService.class).hasScopedAccountBindings(),
        "DeleteUserAccountService");
    require(
        context
            .getBean(KeycloakService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(ClaimedPasswordMaintenance.class),
                context.getBean(ScopedUsernameAvailability.class)),
        "KeycloakService");
    require(
        context
            .getBean(SelfAppOtpMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "SelfAppOtpMaintenance");
    require(
        context
            .getBean(SelfPasswordMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "SelfPasswordMaintenance");
    require(
        context
            .getBean(UserController.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(SelfAppOtpMaintenance.class),
                context.getBean(SelfPasswordMaintenance.class),
                context.getBean(SelfLocaleMaintenance.class)),
        "UserController");
    require(
        context
            .getBean(AskerUserAdminFacade.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(ScopedAskerReactivation.class)),
        "AskerUserAdminFacade");
    require(
        context
            .getBean(ManagedAccountStatusService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "ManagedAccountStatusService");
    require(
        context
            .getBean(CreateAdminService.class)
            .hasScopedAccountBindings(context.getBean(OrdinaryAccountCreation.class)),
        "CreateAdminService");
    require(
        context
            .getBean(DeleteAdminService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "DeleteAdminService");
    require(
        context
            .getBean(UpdateAdminService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "UpdateAdminService");
    require(
        context
            .getBean(ConsultantAdminService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class)),
        "ConsultantAdminService");
    require(
        context
            .getBean(CreateConsultantSaga.class)
            .hasScopedAccountBindings(context.getBean(OrdinaryAccountCreation.class)),
        "CreateConsultantSaga");
    require(
        context
            .getBean(GrantConsultantIdentityService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "GrantConsultantIdentityService");
    require(
        context
            .getBean(ConsultantUpdateService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "ConsultantUpdateService");
    require(
        context
            .getBean(CreateUserFacade.class)
            .hasScopedAccountBindings(context.getBean(OrdinaryRegistrationLifecycle.class)),
        "CreateUserFacade");
    require(
        context
            .getBean(KeycloakUserDataProvider.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(SelfAppOtpMaintenance.class)),
        "KeycloakUserDataProvider");
    require(
        context
            .getBean(ConsultantImportService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(ExistingConsultantImportMaintenance.class),
                context.getBean(CheckedAccountPolicyReads.class),
                context.getBean(JpaTransactionManager.class)),
        "ConsultantImportService");
    require(
        context
            .getBean(AccountInviteService.class)
            .hasScopedAccountBindings(
                context.getBean(ProtectedInvitationIssuance.class),
                context.getBean(ProtectedInvitationCancellation.class),
                context.getBean(JpaTransactionManager.class)),
        "AccountInviteService");
    require(
        context
            .getBean(AccountRoleAddition.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(HumanAdminAccountMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "AccountRoleAddition");
    require(
        context
            .getBean(AgencyAdminInviteProvisioningService.class)
            .hasScopedAccountBindings(context.getBean(OrdinaryAccountCreation.class)),
        "AgencyAdminInviteProvisioningService");
    require(
        context
            .getBean(CounsellorInviteProvisioningService.class)
            .hasScopedAccountBindings(context.getBean(OrdinaryAccountCreation.class)),
        "CounsellorInviteProvisioningService");
    require(
        context
            .getBean(HumanAdminAccountMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(JpaTransactionManager.class)),
        "HumanAdminAccountMaintenance");
    require(
        context
            .getBean(UserAccountService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(PublicPreDeletionMaintenance.class),
                context.getBean(SelfContactEmailMaintenance.class)),
        "UserAccountService");
    require(
        context
            .getBean(AccountInactivityService.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(AccountInactivityIdentityTransport.class),
                context.getBean(DefaultAccountInactivityEffects.class),
                context.getBean(JpaTransactionManager.class)),
        "AccountInactivityService");
    require(
        context
            .getBean(DefaultAccountInactivityEffects.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(ScheduledAskerMaintenance.class)),
        "DefaultAccountInactivityEffects");
    require(
        context
            .getBean(DeleteUserAccountScheduler.class)
            .hasScopedAccountBindings(
                context.getBean(ScheduledAskerMaintenance.class),
                context.getBean(ScheduledConsultantMaintenance.class),
                context.getBean(ScopedAskerReactivation.class)),
        "DeleteUserAccountScheduler");
    require(
        context
            .getBean(PublicPreDeletionMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(IdentityClientConfig.class),
                context.getBean(ConsultantAgencyDeletionValidationService.class),
                context.getBean(JpaTransactionManager.class)),
        "PublicPreDeletionMaintenance");
    require(
        context
            .getBean(ScheduledAskerMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(DeleteUserAccountService.class),
                context.getBean(JpaTransactionManager.class)),
        "ScheduledAskerMaintenance");
    require(
        context
            .getBean(ScheduledConsultantMaintenance.class)
            .hasScopedAccountBindings(
                context.getBean(DeleteUserAccountService.class),
                context.getBean(JpaTransactionManager.class),
                context.getBean(PublicPreDeletionMaintenance.class)),
        "ScheduledConsultantMaintenance");
  }

  private static void require(boolean installed, String consumer) {
    if (!installed)
      throw new IllegalStateException("Incomplete scoped account binding: " + consumer);
  }
}
