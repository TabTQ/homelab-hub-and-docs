#!/usr/bin/env python3
"""Records Wake-on-LAN magic packets sent to a local UDP port (for end-to-end tests). Usage: wol_listener.py PORT LOGFILE"""
import socket
import sys

port, log = int(sys.argv[1]), sys.argv[2]
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", port))
print(f"wol listener on udp/{port}", flush=True)
while True:
    data, _ = s.recvfrom(512)
    ok = len(data) == 102 and data[:6] == b"\xff" * 6 and all(data[6 + i * 6:12 + i * 6] == data[6:12] for i in range(16))
    mac = ":".join(f"{b:02X}" for b in data[6:12])
    with open(log, "a") as f:
        f.write(f"{'MAGIC' if ok else 'BAD'} {mac}\n")
