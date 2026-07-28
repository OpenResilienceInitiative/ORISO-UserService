#!/usr/bin/env python3
"""Serve a disposable JWK and write one matching consultant JWT for replica tests."""

from __future__ import annotations

import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import time


KEY_ID = "userservice-replica-proof"


def base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def generate_key(private_key: Path) -> None:
    subprocess.run(
        [
            "openssl",
            "genpkey",
            "-algorithm",
            "RSA",
            "-pkeyopt",
            "rsa_keygen_bits:2048",
            "-out",
            str(private_key),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    private_key.chmod(0o600)


def modulus(private_key: Path) -> str:
    output = subprocess.check_output(
        ["openssl", "rsa", "-in", str(private_key), "-noout", "-modulus"],
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()
    prefix = "Modulus="
    if not output.startswith(prefix):
        raise RuntimeError("OpenSSL did not return an RSA modulus")
    return base64url(bytes.fromhex(output.removeprefix(prefix)))


def sign_jwt(private_key: Path) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "kid": KEY_ID, "typ": "JWT"}
    claims = {
        "sub": "tutorial-replica-jwt-user",
        "preferred_username": "tutorial-replica-consultant",
        "username": "tutorial-replica-consultant",
        "tenantId": 1,
        "realm_access": {"roles": ["consultant"]},
        "iat": now - 30,
        "exp": now + 3600,
    }
    encoded_header = base64url(
        json.dumps(header, separators=(",", ":"), sort_keys=True).encode()
    )
    encoded_claims = base64url(
        json.dumps(claims, separators=(",", ":"), sort_keys=True).encode()
    )
    signing_input = f"{encoded_header}.{encoded_claims}".encode("ascii")
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
        input=signing_input,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    ).stdout
    return f"{signing_input.decode('ascii')}.{base64url(signature)}"


def create_server(host: str, port: int, jwk_set: dict[str, object]) -> ThreadingHTTPServer:
    payload = json.dumps(jwk_set, separators=(",", ":")).encode()

    class JwkHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path == "/health":
                response = b'{"status":"UP"}'
            elif self.path.endswith("/certs"):
                response = payload
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, _format: str, *_args: object) -> None:
            pass

    return ThreadingHTTPServer((host, port), JwkHandler)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    args = parser.parse_args()

    generate_key(args.private_key)
    args.token_file.write_text(sign_jwt(args.private_key), encoding="utf-8")
    args.token_file.chmod(0o600)
    jwk_set = {
        "keys": [
            {
                "kty": "RSA",
                "use": "sig",
                "alg": "RS256",
                "kid": KEY_ID,
                "n": modulus(args.private_key),
                "e": "AQAB",
            }
        ]
    }
    server = create_server(args.host, args.port, jwk_set)
    print(f"Disposable JWK endpoint listening on {args.host}:{server.server_port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if args.token_file.exists():
            os.chmod(args.token_file, 0o600)


if __name__ == "__main__":
    main()
