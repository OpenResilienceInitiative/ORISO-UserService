# Onboarding Guide: ORISO-UserService

1. Read `README.md` in the repository root if present.
2. Open `.understand-anything/README.md` and launch the dashboard using the command shown there.
3. Start with these tour files:

- `README.md` - README.md is a docs file under repository root; starts with "# Online-Beratung UserService".
- `package.json` - package.json is a config file under repository root; starts with ""name": "online-beratung-userservice",".
- `pom.xml` - pom.xml is a config file under repository root; starts with "?xml version="1.0" encoding="UTF-8"?".
- `src/main/java/de/caritas/cob/userservice/api/AccountManager.java` - src/main/java/de/caritas/cob/userservice/api/AccountManager.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/actions/ActionCommand.java` - src/main/java/de/caritas/cob/userservice/api/actions/ActionCommand.java is a code file under src; starts with "public interface ActionCommandT".
- `src/main/java/de/caritas/cob/userservice/api/actions/chat/ChatReCreator.java` - src/main/java/de/caritas/cob/userservice/api/actions/chat/ChatReCreator.java is a code file under src in this repository.
- `.github/actions/docker-build-push/action.yml` - .github/actions/docker-build-push/action.yml is a config file under .github; starts with "name: Reusable Docker Build and Publish steps".
- `.github/actions/maven-build/action.yml` - .github/actions/maven-build/action.yml is a config file under .github; starts with "name: Reusable Maven Build steps".
- `.mvn/wrapper/maven-wrapper.properties` - .mvn/wrapper/maven-wrapper.properties is a config file under .mvn; starts with "wrapperVersion=3.3.4".
- `api/appointmentservice.yaml` - api/appointmentservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/conversationservice.yaml` - api/conversationservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/useradminservice.yaml` - api/useradminservice.yaml is a config file under api; starts with "openapi: 3.0.1".

4. Review architecture layers in `.understand-anything/ARCHITECTURE.md`.
5. For changes, inspect files connected by `imports`, `configures`, `routes`, `deploys`, and `tested_by` edges in the graph.
