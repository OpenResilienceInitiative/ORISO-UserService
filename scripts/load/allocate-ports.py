#!/usr/bin/env python3
"""Prints N free localhost ports below the kernel's ephemeral range, where Docker picks the
random host ports it publishes; a bind(0) port there can go to a container first."""

from pathlib import Path
import random
import socket
import sys

LOWEST_PORT = 20000
DEFAULT_EPHEMERAL_START = 32768


def ephemeral_range_start():
    range_file = Path("/proc/sys/net/ipv4/ip_local_port_range")
    try:
        return min(int(range_file.read_text().split()[0]), DEFAULT_EPHEMERAL_START)
    except (OSError, ValueError, IndexError):
        return DEFAULT_EPHEMERAL_START


def is_free(port):
    with socket.socket() as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def allocate(count):
    candidates = list(range(LOWEST_PORT, ephemeral_range_start()))
    random.shuffle(candidates)
    ports = []
    for port in candidates:
        if is_free(port):
            ports.append(port)
            if len(ports) == count:
                return ports
    raise SystemExit(f"Only {len(ports)} of {count} free ports below the ephemeral range")


if __name__ == "__main__":
    print(" ".join(str(port) for port in allocate(int(sys.argv[1]))))
