"""Finite actual realm APP/email login fixture; no production authority or exported codes."""
import hashlib
import http.server
import json
import threading
from pathlib import Path
from urllib.parse import quote

SOURCE = "7ed5ee7cd4b4f1c0cad4c00aba87c93906e59654"
ALIASES = {"direct-grant-2fa", "app-otp-conditional", "email-otp-conditional"}


def install(fixture, input_path):
    raw = Path(input_path).read_bytes()
    data = json.loads(raw)
    assert data["sourceCommit"] == SOURCE
    assert data["sourcePath"] == "charts/keycloak/keycloak-resources/realm.json"
    assert data["directGrantFlow"] == "direct-grant-2fa"
    flows = {row["alias"]: row for row in data["authenticationFlows"]}
    assert set(flows) == ALIASES and len(data["authenticationFlows"]) == 3
    configs = {row["alias"]: row for row in data["authenticatorConfig"]}
    assert set(configs) == {"email-otp-config"}
    top = flows["direct-grant-2fa"]
    fixture.admin("POST", "/authentication/flows", {key: top[key] for key in
        ["alias", "description", "providerId", "topLevel", "builtIn"]})

    def add_entries(alias):
        for item in flows[alias]["authenticationExecutions"]:
            prefix = "/authentication/flows/" + quote(alias, safe="") + "/executions"
            if item["authenticatorFlow"]:
                child = flows[item["flowAlias"]]
                fixture.admin("POST", prefix + "/flow", {
                    "alias": child["alias"], "type": child["providerId"],
                    "provider": child["providerId"], "priority": item["priority"],
                    "description": child.get("description", "")})
                rows = fixture.admin("GET", prefix)
                selected = [row for row in rows if row.get("level") == 0
                    and row.get("authenticationFlow") is True and row.get("displayName") == child["alias"]]
            else:
                fixture.admin("POST", prefix + "/execution", {
                    "provider": item["authenticator"], "priority": item["priority"]})
                rows = fixture.admin("GET", prefix)
                selected = [row for row in rows if row.get("level") == 0
                    and row.get("authenticationFlow") is not True and row.get("providerId") == item["authenticator"]]
            assert len(selected) == 1
            current = selected[0]
            current["requirement"] = item["requirement"]
            fixture.admin("PUT", prefix, current)
            if item.get("authenticatorConfig"):
                fixture.admin("POST", "/authentication/executions/" + current["id"] + "/config",
                    configs[item["authenticatorConfig"]])
            if item["authenticatorFlow"]:
                add_entries(item["flowAlias"])
        observed = fixture.admin("GET", "/authentication/flows/" + quote(alias, safe="") + "/executions")
        observed = [row for row in observed if row.get("level") == 0]
        assert len(observed) == len(flows[alias]["authenticationExecutions"])
        for actual, expected in zip(observed, flows[alias]["authenticationExecutions"]):
            assert actual["requirement"] == expected["requirement"]
            assert bool(actual.get("authenticationFlow")) == expected["authenticatorFlow"]
            if expected["authenticatorFlow"]:
                assert actual["displayName"] == expected["flowAlias"]
            else:
                assert actual["providerId"] == expected["authenticator"]
                if expected.get("authenticatorConfig"):
                    installed = fixture.admin("GET", "/authentication/config/" + actual["authenticationConfig"])
                    assert installed["alias"] == expected["authenticatorConfig"]
                    assert installed["config"] == configs[expected["authenticatorConfig"]]["config"]

    add_entries(top["alias"])
    realm = fixture.admin("GET")
    realm["directGrantFlow"] = top["alias"]
    realm["emailTheme"] = "oriso"
    realm["smtpServer"] = {"host": "host.docker.internal", "port": str(fixture.mail.server_address[1]),
        "from": "otp-fixture@oriso.org", "auth": "false", "ssl": "false", "starttls": "false"}
    fixture.admin("PUT", "", realm)
    actual = fixture.admin("GET")
    assert actual["directGrantFlow"] == top["alias"] and actual["smtpServer"] == realm["smtpServer"]
    return {"sourceCommit": SOURCE, "sourcePath": data["sourcePath"], "sourceSHA256": data["sourceSHA256"],
        "inputSHA256": hashlib.sha256(raw).hexdigest(), "actualAppAndEmailConditionalGraphVerified": True,
        "actualNativeSmtpConfigured": True}


def serve_codes(mail, delivered_code):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            count = {"/enrollment": 1, "/login": 2}.get(self.path)
            if count is None:
                self.send_error(404)
                return
            messages = list(mail.messages)
            if len(messages) != count:
                self.send_response(409)
                self.end_headers()
                return
            try:
                code = delivered_code([messages[-1]])
            except ValueError:
                self.send_response(409)
                self.end_headers()
                return
            body = json.dumps({"code": code}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.worker = threading.Thread(target=server.serve_forever, daemon=True)
    server.worker.start()
    return server
