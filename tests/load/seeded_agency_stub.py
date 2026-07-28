#!/usr/bin/env python3
"""Deterministic AgencyService batch-read stub for the seeded load scenario."""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class SeededAgencyHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        prefix = "/agencies/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return

        raw_ids = self.path.removeprefix(prefix).split("?", 1)[0]
        try:
            agency_ids = [int(value) for value in raw_ids.split(",")]
        except ValueError:
            self.send_error(400)
            return

        payload = json.dumps(
            [
                {
                    "id": agency_id,
                    "name": f"Seeded Agency {agency_id}",
                    "postcode": "10115",
                    "city": "Berlin",
                    "description": "Seeded load-test agency",
                    "teamAgency": False,
                    "offline": False,
                    "consultingType": 1,
                    "tenantId": 0,
                    "topicIds": [1, 2],
                }
                for agency_id in agency_ids
            ]
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format: str, *_args: object) -> None:
        pass


def create_server(host: str, port: int) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), SeededAgencyHandler)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Serve the seeded AgencyService batch-read contract."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18083)
    args = parser.parse_args()
    server = create_server(args.host, args.port)
    print(f"Seeded AgencyService stub listening on {args.host}:{server.server_port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
