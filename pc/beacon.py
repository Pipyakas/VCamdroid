#!/usr/bin/env python3
"""
VCamdroid PC beacon — zero-config LAN discovery.

Broadcasts a tiny "VCAMDROID1" datagram once a second so any VCamdroid fork app on the
same subnet finds this PC and connects to the VCamdroid server (TCP 6969) without a QR
code, a manual IP entry, or a USB cable.

The phone is the listener: it binds UDP 6970 and takes the sender's IP from the first
datagram it sees (see android/.../networking/BeaconListener.kt).

Run alongside the VCamdroid Windows client (which owns TCP 6969 / UDP 8554).
Stdlib only; no firewall prompt for the phone itself, only for this script's outbound
broadcast the first time you run it.

Usage:
    python beacon.py            # broadcast on every interface, 1 Hz
    python beacon.py --port 6970 --once
"""

import argparse
import socket
import sys
import time

MAGIC = b"VCAMDROID1"
BROADCAST = "255.255.255.255"


def local_ipv4_addresses():
    """Best-effort list of this host's IPv4 addresses without extra dependencies."""
    addrs = set()
    # Ask the OS for the preferred outbound address (works everywhere, no traffic sent).
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        addrs.add(s.getsockname()[0])
    except OSError:
        pass
    finally:
        s.close()
    # Resolve the hostname as a second source.
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None, socket.AF_INET):
            addrs.add(info[4][0])
    except OSError:
        pass
    return sorted(a for a in addrs if not a.startswith("127."))


def main() -> int:
    ap = argparse.ArgumentParser(description="VCamdroid LAN beacon")
    ap.add_argument("--port", type=int, default=6970, help="UDP port the phone listens on")
    ap.add_argument("--server-port", type=int, default=6969, help="TCP port of the VCamdroid server")
    ap.add_argument("--interval", type=float, default=1.0, help="seconds between beacons")
    ap.add_argument("--once", action="store_true", help="send a single beacon and exit")
    args = ap.parse_args()

    addrs = local_ipv4_addresses()
    payload = MAGIC + b" tcp=%d" % args.server_port

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    # Belt and braces: also send to each interface's subnet broadcast where we can.
    targets = {BROADCAST}
    for a in addrs:
        parts = a.split(".")
        if len(parts) == 4:
            targets.add(".".join(parts[:3]) + ".255")

    print(f"beacon: udp/{args.port} every {args.interval}s -> {', '.join(sorted(targets))}")
    print(f"this PC: {', '.join(addrs) or '(no address found)'}  (server tcp/{args.server_port})")
    sys.stdout.flush()

    try:
        while True:
            for t in targets:
                try:
                    sock.sendto(payload, (t, args.port))
                except OSError:
                    pass
            if args.once:
                return 0
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())