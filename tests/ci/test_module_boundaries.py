from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CONTROLLERS = (
    ROOT
    / "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller"
)
WEB_MAPPINGS = (
    ROOT
    / "src/main/java/de/caritas/cob/userservice/api/adapters/web/mapping"
)
USERDATA_FACADES = ROOT / "src/main/java/de/caritas/cob/userservice/api/facade/userdata"


class ModuleBoundaryContractTest(unittest.TestCase):
    def test_user_and_appointment_web_slices_depend_on_input_ports(self):
        sources = [
            CONTROLLERS / "AppointmentController.java",
            CONTROLLERS / "ConversationController.java",
            CONTROLLERS / "UserController.java",
            *CONTROLLERS.glob("User*ControllerDelegate.java"),
        ]
        forbidden = (
            "import de.caritas.cob.userservice.api.AccountManager;",
            "import de.caritas.cob.userservice.api.Messenger;",
            "import de.caritas.cob.userservice.api.Organizer;",
            "import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;",
            "import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;",
        )
        offenders = []

        for source in sources:
            text = source.read_text()
            for forbidden_import in forbidden:
                if forbidden_import in text:
                    offenders.append(
                        f"{source.relative_to(ROOT)} imports {forbidden_import}"
                    )

        self.assertEqual(
            [],
            offenders,
            "User and appointment web adapters must call input ports, not concrete "
            "application services or outbound adapters:\n" + "\n".join(offenders),
        )

    def test_session_module_depends_on_ports_not_chat_adapters(self):
        session_module = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/session"
        )
        forbidden_prefixes = (
            "import de.caritas.cob.userservice.api.adapters.matrix.",
            "import de.caritas.cob.userservice.api.adapters.rocketchat.",
        )
        offenders = []

        for source in session_module.glob("*.java"):
            for line in source.read_text().splitlines():
                if line.startswith(forbidden_prefixes):
                    offenders.append(f"{source.relative_to(ROOT)} imports {line}")

        self.assertEqual(
            [],
            offenders,
            "The session/consultant application module must use outbound ports "
            "instead of concrete chat adapters:\n" + "\n".join(offenders),
        )

    def test_identity_profile_module_depends_on_ports_not_identity_or_chat_adapters(self):
        application_roots = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/identity",
            ROOT / "src/main/java/de/caritas/cob/userservice/api/service/user",
        )
        forbidden_prefixes = (
            "import de.caritas.cob.userservice.api.adapters.keycloak.",
            "import de.caritas.cob.userservice.api.adapters.matrix.",
            "import de.caritas.cob.userservice.api.adapters.rocketchat.",
        )
        offenders = []

        for application_root in application_roots:
            for source in application_root.rglob("*.java"):
                for line in source.read_text().splitlines():
                    if line.startswith(forbidden_prefixes):
                        offenders.append(f"{source.relative_to(ROOT)} imports {line}")

        self.assertEqual(
            [],
            offenders,
            "The identity/profile application module must use ports instead of "
            "concrete identity or chat adapters:\n" + "\n".join(offenders),
        )

    def test_identity_email_owner_lookup_uses_a_focused_typed_port(self):
        identity_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        identity_manager = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/IdentityManager.java"
        ).read_text()

        self.assertNotIn(
            "findUserByEmail(",
            identity_port,
            "The broad identity command port must not expose email-owner reads",
        )
        self.assertIn(
            "IdentityEmailOwnerLookup",
            identity_manager,
            "IdentityManager must use the focused typed email-owner lookup",
        )
        self.assertNotIn(
            '"encodedUsername"',
            identity_manager,
            "Application code must not interpret Keycloak adapter map keys",
        )
        self.assertNotIn(
            '"decodedUsername"',
            identity_manager,
            "Application code must not interpret Keycloak adapter map keys",
        )

    def test_identity_authentication_uses_a_focused_provider_neutral_port(self):
        identity_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        authentication_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityAuthentication.java"
        )
        consumers = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/IdentityManager.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/conversation/service/user/"
            "anonymous/AnonymousUserCreatorService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/agency/"
            "AgencyMatrixCredentialClient.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/appointment/"
            "AppointmentService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/user/validation/"
            "UserAccountValidator.java",
        )

        self.assertTrue(
            authentication_port.exists(),
            "A focused IdentityAuthentication port must own authentication",
        )
        authentication_contract = authentication_port.read_text()
        self.assertIn(
            "IdentityLogin login(",
            authentication_contract,
            "Authentication must return the provider-neutral login value",
        )
        self.assertNotIn("adapters.keycloak", authentication_contract)
        self.assertNotIn("org.keycloak", authentication_contract)

        for method in ("loginUser(", "logoutUser(", "verifyIgnoringOtp("):
            self.assertNotIn(
                method,
                identity_port,
                "The broad identity command port must not expose authentication",
            )

        offenders = [
            str(source.relative_to(ROOT))
            for source in consumers
            if "IdentityAuthentication" not in source.read_text()
        ]
        self.assertEqual(
            [],
            offenders,
            "All live production authentication consumers must use the focused port:\n"
            + "\n".join(offenders),
        )

    def test_username_availability_uses_a_focused_provider_neutral_port(self):
        identity_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        availability_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityUsernameAvailability.java"
        )
        consumers = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/IdentityManager.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/conversation/service/user/"
            "anonymous/AnonymousUsernameRegistry.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/"
            "ConsultantImportService.java",
        )
        user_verifier = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/helper/UserVerifier.java"
        ).read_text()

        self.assertTrue(
            availability_port.exists(),
            "A focused IdentityUsernameAvailability port must own availability reads",
        )
        self.assertNotIn(
            "isUsernameAvailable(",
            identity_port,
            "The broad identity command port must not expose username availability",
        )

        offenders = [
            str(source.relative_to(ROOT))
            for source in consumers
            if "IdentityUsernameAvailability" not in source.read_text()
        ]
        self.assertEqual(
            [],
            offenders,
            "All current availability consumers must use the focused port:\n"
            + "\n".join(offenders),
        )
        self.assertNotIn(
            "IdentityClient",
            user_verifier,
            "UserVerifier must not retain an unused broad identity dependency",
        )

    def test_second_factor_verification_uses_typed_application_boundaries(self):
        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        second_factor_port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentitySecondFactor.java"
        )
        identity_input = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/in/"
            "IdentityManaging.java"
        ).read_text()
        identity_manager = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/IdentityManager.java"
        ).read_text()
        two_factor_delegate = (
            CONTROLLERS / "UserTwoFactorAuthControllerDelegate.java"
        ).read_text()
        typed_values = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/identity/"
            "IdentityOtpCredential.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/identity/"
            "IdentityOtpType.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/identity/"
            "IdentityEmailVerification.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/identity/"
            "IdentityEmailVerificationStart.java",
        )

        self.assertTrue(
            second_factor_port.exists(),
            "A focused IdentitySecondFactor port must own OTP and email verification",
        )
        self.assertTrue(
            all(value.exists() for value in typed_values),
            "Second-factor boundaries must use typed provider-neutral application values",
        )
        for method in (
            "getOtpCredential(",
            "setUpOtpCredential(",
            "deleteOtpCredential(",
            "initiateEmailVerification(",
            "finishEmailVerification(",
        ):
            self.assertNotIn(
                method,
                identity_client,
                "The broad identity command port must not expose second-factor verification",
            )
        self.assertNotIn(
            "OtpInfoDTO",
            identity_client,
            "The broad identity command port must not import a generated web DTO",
        )
        self.assertIn(
            "IdentitySecondFactor",
            identity_manager,
            "IdentityManager must use the focused second-factor output port",
        )
        for source in (identity_input, identity_manager):
            self.assertNotIn(
                "OtpInfoDTO",
                source,
                "The application identity boundary must not expose the generated OTP DTO",
            )
            self.assertNotIn(
                "Map<String, String>",
                source,
                "The application identity boundary must not expose stringly verification maps",
            )
        self.assertNotIn(
            'validationResult.get("',
            two_factor_delegate,
            "The web delegate must consume a typed email-verification result",
        )

        keycloak_adapter = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/"
            "KeycloakService.java"
        ).read_text()
        for operation in (
            "otp-fetch",
            "otp-setup",
            "otp-delete",
            "email-verification-start",
            "email-verification-finish",
        ):
            self.assertIn(
                f'"{operation}"',
                keycloak_adapter,
                "Second-factor retries must retain stable per-operation metric tags",
            )

    def test_username_availability_web_boundaries_depend_on_identity_input_port(self):
        sources = (
            CONTROLLERS / "UserController.java",
            CONTROLLERS / "UserRegistrationControllerDelegate.java",
        )
        forbidden_imports = (
            "import de.caritas.cob.userservice.api.port.out.IdentityClient;",
            "import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;",
        )
        offenders = [
            f"{source.relative_to(ROOT)} imports {forbidden_import}"
            for source in sources
            for forbidden_import in forbidden_imports
            if forbidden_import in source.read_text()
        ]
        offenders.extend(
            f"{source.relative_to(ROOT)} does not import IdentityManaging"
            for source in sources
            if "import de.caritas.cob.userservice.api.port.in.IdentityManaging;"
            not in source.read_text()
        )

        self.assertEqual(
            [],
            offenders,
            "Username-availability web adapters must call IdentityManaging instead "
            "of bypassing the application boundary through an output port:\n"
            + "\n".join(offenders),
        )

    def test_magic_link_application_and_web_boundaries_do_not_import_keycloak_transport(self):
        sources = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/auth/"
            "MagicLinkLoginService.java",
            CONTROLLERS / "UserController.java",
            CONTROLLERS / "UserRegistrationControllerDelegate.java",
        )
        forbidden_prefix = (
            "import de.caritas.cob.userservice.api.adapters.keycloak."
        )
        offenders = [
            f"{source.relative_to(ROOT)} imports {line}"
            for source in sources
            for line in source.read_text().splitlines()
            if line.startswith(forbidden_prefix)
        ]

        self.assertEqual(
            [],
            offenders,
            "Magic-link application and web boundaries must expose "
            "application-owned sessions instead of Keycloak transport DTOs:\n"
            + "\n".join(offenders),
        )

    def test_magic_link_web_response_does_not_depend_on_output_port_models(self):
        response_dto = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/adapters/web/dto/"
            "MagicLinkSessionResponseDTO.java"
        )
        offenders = [
            line
            for line in response_dto.read_text().splitlines()
            if line.startswith("import de.caritas.cob.userservice.api.port.out.")
        ]

        self.assertEqual(
            [],
            offenders,
            "The magic-link web response must map an application/domain model, "
            "not expose the outbound-port package:\n" + "\n".join(offenders),
        )

    def test_user_web_mappers_do_not_import_outbound_identity_client(self):
        forbidden_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityClient;"
        )
        offenders = [
            str(source.relative_to(ROOT))
            for source in WEB_MAPPINGS.glob("*.java")
            if forbidden_import in source.read_text()
        ]

        self.assertEqual(
            [],
            offenders,
            "User web mappers must ask the identity input port instead of calling "
            "the outbound identity client:\n" + "\n".join(offenders),
        )

    def test_user_data_facades_use_a_focused_identity_profile_port(self):
        port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityProfileLookup.java"
        )
        self.assertTrue(
            port.exists(),
            "Authenticated profile reads need a focused provider-neutral output port",
        )

        broad_client_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityClient;"
        )
        offenders = [
            str(source.relative_to(ROOT))
            for source in USERDATA_FACADES.glob("*.java")
            if broad_client_import in source.read_text()
        ]
        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()

        self.assertEqual(
            [],
            offenders,
            "User-data facades must depend on the focused profile-read port:\n"
            + "\n".join(offenders),
        )
        self.assertNotIn(
            "getById(",
            identity_client,
            "The broad identity command client must not own profile reads",
        )
        spring_identity_mocks = [
            source
            for source in (ROOT / "src/test/java").rglob("*.java")
            if "@MockitoBean" in source.read_text()
            and "IdentityClient identityClient" in source.read_text()
        ]
        missing_test_interface = [
            str(source.relative_to(ROOT))
            for source in spring_identity_mocks
            if "IdentityProfileLookup" not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_test_interface,
            "Shared Spring identity mocks must provide the focused profile-read port:\n"
            + "\n".join(missing_test_interface),
        )

    def test_profile_write_consumers_use_the_focused_identity_profile_port(self):
        port_root = ROOT / "src/main/java/de/caritas/cob/userservice/api/port/out"
        updater = port_root / "IdentityProfileUpdater.java"
        profile = port_root / "IdentityProfileUpdate.java"
        self.assertTrue(updater.exists(), "Profile writes need a focused output port")
        self.assertTrue(profile.exists(), "Profile writes need a provider-neutral value")

        forbidden_model_imports = (
            "de.caritas.cob.userservice.api.adapters.web.",
            "org.keycloak.",
        )
        model_text = profile.read_text()
        for forbidden_import in forbidden_model_imports:
            self.assertNotIn(
                forbidden_import,
                model_text,
                "The profile-write value must not expose web or Keycloak transport types",
            )

        focused_updater_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdater;"
        )
        consumers = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/admin/update/"
            "UpdateAdminService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/update/"
            "ConsultantUpdateService.java",
        )
        missing_focused_port = [
            str(source.relative_to(ROOT))
            for source in consumers
            if focused_updater_import not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_focused_port,
            "Profile writers must depend on IdentityProfileUpdater:\n"
            + "\n".join(missing_focused_port),
        )
        for source in consumers:
            self.assertNotIn(
                "identityClient.updateUserData(",
                source.read_text(),
                f"{source.name} must write profiles through the focused port",
            )

        identity_client = (port_root / "IdentityClient.java").read_text()
        self.assertNotIn(
            "updateUserData(",
            identity_client,
            "The broad identity command client must not own profile updates",
        )

        spring_identity_mocks = [
            source
            for source in (ROOT / "src/test/java").rglob("*.java")
            if "@MockitoBean" in source.read_text()
            and "IdentityClient identityClient" in source.read_text()
        ]
        missing_test_interface = [
            str(source.relative_to(ROOT))
            for source in spring_identity_mocks
            if "IdentityProfileUpdater" not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_test_interface,
            "Shared Spring identity mocks must provide the focused profile-write port:\n"
            + "\n".join(missing_test_interface),
        )

    def test_role_read_consumers_use_a_focused_identity_role_port(self):
        focused_lookup_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;"
        )
        consumers = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant"
            / "create/agencyrelation/ConsultantAgencyRelationCreatorService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/identity"
            / "UserIdentitiesService.java",
        )
        missing_focused_port = [
            str(source.relative_to(ROOT))
            for source in consumers
            if focused_lookup_import not in source.read_text()
        ]
        user_identities_service = consumers[1].read_text()
        agency_relation_service = consumers[0].read_text()
        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()

        self.assertEqual(
            [],
            missing_focused_port,
            "Realm-role readers must depend on a focused role-read port:\n"
            + "\n".join(missing_focused_port),
        )
        self.assertNotIn(
            "import de.caritas.cob.userservice.api.port.out.IdentityClient;",
            user_identities_service,
            "UserIdentitiesService must not depend on the broad command client",
        )
        self.assertNotIn(
            "identityClient.userHasRole(",
            agency_relation_service,
            "Agency role-set validation must use one focused realm-role read",
        )
        self.assertNotIn(
            "getRealmRoles(",
            identity_client,
            "The broad identity command client must not own realm-role reads",
        )

    def test_email_mutation_consumers_use_a_focused_identity_email_port(self):
        port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityEmailAddressUpdater.java"
        )
        self.assertTrue(
            port.exists(),
            "Account email mutations need a focused provider-neutral output port",
        )
        if not port.exists():
            return

        focused_import = (
            "import de.caritas.cob.userservice.api.port.out."
            "IdentityEmailAddressUpdater;"
        )
        identity_manager = (
            ROOT / "src/main/java/de/caritas/cob/userservice/api/IdentityManager.java"
        ).read_text()
        user_account_service = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/user/"
            "UserAccountService.java"
        ).read_text()
        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        keycloak_adapter = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/"
            "KeycloakService.java"
        ).read_text()

        for source in (identity_manager, user_account_service, keycloak_adapter):
            self.assertIn(
                focused_import,
                source,
                "Every email-mutation participant must use the focused output port",
            )
        self.assertNotIn(
            "identityClient.changeEmailAddress(",
            identity_manager + user_account_service,
            "Application email changes must not use the broad identity client",
        )
        self.assertNotIn(
            "identityClient.deleteEmailAddress(",
            user_account_service,
            "Application email deletion must not use the broad identity client",
        )
        for method in ("changeEmailAddress(", "deleteEmailAddress(", "updateEmail("):
            self.assertNotIn(
                method,
                identity_client,
                "The broad identity command client must not own email mutations",
            )
        spring_identity_mocks = [
            source
            for source in (ROOT / "src/test/java").rglob("*.java")
            if "@MockitoBean" in source.read_text()
            and "IdentityClient identityClient" in source.read_text()
        ]
        missing_test_interface = [
            str(source.relative_to(ROOT))
            for source in spring_identity_mocks
            if "IdentityEmailAddressUpdater" not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_test_interface,
            "Shared Spring identity mocks must provide the focused email port:\n"
            + "\n".join(missing_test_interface),
        )

    def test_identity_boundary_has_no_unused_session_close_command(self):
        sources = [
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/"
            "KeycloakService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/"
            "KeycloakAuthClient.java",
        ]
        offenders = [
            str(source.relative_to(ROOT))
            for source in sources
            if "closeSession(" in source.read_text()
        ]
        self.assertEqual(
            [],
            offenders,
            "The unused identity session-close command must not remain on production surfaces:\n"
            + "\n".join(offenders),
        )

    def test_admin_module_depends_on_ports_not_chat_adapters(self):
        admin_module = ROOT / "src/main/java/de/caritas/cob/userservice/api/admin"
        forbidden_prefixes = (
            "import de.caritas.cob.userservice.api.adapters.matrix.",
            "import de.caritas.cob.userservice.api.adapters.rocketchat.",
        )
        offenders = []

        for source in admin_module.rglob("*.java"):
            for line in source.read_text().splitlines():
                if line.startswith(forbidden_prefixes):
                    offenders.append(f"{source.relative_to(ROOT)} imports {line}")

        self.assertEqual(
            [],
            offenders,
            "The admin application module must use outbound ports instead of "
            "concrete chat adapters:\n" + "\n".join(offenders),
        )

    def test_session_assignment_module_depends_on_ports_not_chat_adapters(self):
        assignment_module = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/facade/assignsession"
        )
        forbidden_prefixes = (
            "import de.caritas.cob.userservice.api.adapters.matrix.",
            "import de.caritas.cob.userservice.api.adapters.rocketchat.",
            "import de.caritas.cob.userservice.api.admin.service.rocketchat.",
        )
        offenders = []

        for source in assignment_module.glob("*.java"):
            for line in source.read_text().splitlines():
                if line.startswith(forbidden_prefixes):
                    offenders.append(f"{source.relative_to(ROOT)} imports {line}")

        self.assertEqual(
            [],
            offenders,
            "The session-assignment application module must use outbound ports "
            "instead of concrete chat adapters or admin implementation services:\n"
            + "\n".join(offenders),
        )

    def test_password_write_consumers_use_a_focused_identity_port(self):
        port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityPasswordUpdater.java"
        )
        self.assertTrue(
            port.exists(),
            "Identity password writes need a focused provider-neutral output port",
        )
        if not port.exists():
            return

        port_text = port.read_text()
        self.assertNotIn(
            ".adapters.web.",
            port_text,
            "The focused password port must not expose web DTOs",
        )
        self.assertNotIn(
            "org.keycloak.",
            port_text,
            "The focused password port must not expose Keycloak types",
        )

        consumers = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/admin/"
            "create/CreateAdminService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant/"
            "create/CreateConsultantSaga.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/facade/CreateUserFacade.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/auth/"
            "PasswordResetService.java",
        )
        focused_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;"
        )
        for source in consumers:
            source_text = source.read_text()
            self.assertIn(
                focused_import,
                source_text,
                f"{source.relative_to(ROOT)} must use the focused password port",
            )
            self.assertIn(
                "identityPasswordUpdater.updatePassword(",
                source_text,
                f"{source.relative_to(ROOT)} must call the focused password port",
            )
            self.assertNotIn(
                "identityClient.updatePassword(",
                source_text,
                f"{source.relative_to(ROOT)} must not use broad-client password mutation",
            )

        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()
        self.assertNotIn(
            "updatePassword(",
            identity_client,
            "The broad identity command client must not own password mutation",
        )

        spring_identity_mocks = [
            source
            for source in (ROOT / "src/test/java").rglob("*.java")
            if "@MockitoBean" in source.read_text()
            and "IdentityClient identityClient" in source.read_text()
        ]
        missing_test_interface = [
            str(source.relative_to(ROOT))
            for source in spring_identity_mocks
            if "IdentityPasswordUpdater.class" not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_test_interface,
            "Shared Spring identity mocks must implement the focused password port:\n"
            + "\n".join(missing_test_interface),
        )

    def test_consultant_agency_fallback_does_not_retry_agency_service_per_id(self):
        source = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/service/ConsultantAgencyService.java"
        ).read_text()

        self.assertNotIn(
            "agencyService.getAgencyWithoutCaching(",
            source,
            "A failed agency batch must not trigger one outbound retry per agency",
        )
        self.assertNotIn(
            "findDistinctConsultingTypeIdsByAgencyId(",
            source,
            "Fallback consulting types must be loaded in one local batch query",
        )

    def test_role_write_consumers_use_the_focused_batch_identity_port(self):
        port = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/"
            "IdentityRoleUpdater.java"
        )
        self.assertTrue(
            port.exists(),
            "Consultant role writes need a focused provider-neutral batch port",
        )

        focused_updater_import = (
            "import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;"
        )
        consumers = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant"
            / "create/GrantConsultantIdentityService.java",
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/admin/service/consultant"
            / "create/agencyrelation/ConsultantAgencyRelationCreatorService.java",
        )
        missing_focused_port = [
            str(source.relative_to(ROOT))
            for source in consumers
            if focused_updater_import not in source.read_text()
        ]
        identity_client = (
            ROOT
            / "src/main/java/de/caritas/cob/userservice/api/port/out/IdentityClient.java"
        ).read_text()

        self.assertEqual(
            [],
            missing_focused_port,
            "Realm-role writers must depend on the focused batch role-write port:\n"
            + "\n".join(missing_focused_port),
        )
        self.assertNotIn(
            "ensureRole(",
            identity_client,
            "The broad identity command client must not own role ensuring",
        )
        for source in consumers:
            self.assertNotIn(
                "identityClient.ensureRole(",
                source.read_text(),
                f"{source.name} must batch role writes through IdentityRoleUpdater",
            )

        spring_identity_mocks = [
            source
            for source in (ROOT / "src/test/java").rglob("*.java")
            if "@MockitoBean" in source.read_text()
            and "IdentityClient identityClient" in source.read_text()
        ]
        missing_test_interface = [
            str(source.relative_to(ROOT))
            for source in spring_identity_mocks
            if "IdentityRoleUpdater" not in source.read_text()
        ]
        self.assertEqual(
            [],
            missing_test_interface,
            "Shared Spring identity mocks must provide the focused role-write port:\n"
            + "\n".join(missing_test_interface),
        )

if __name__ == "__main__":
    unittest.main()
