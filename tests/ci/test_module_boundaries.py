from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CONTROLLERS = (
    ROOT
    / "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller"
)


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


if __name__ == "__main__":
    unittest.main()
