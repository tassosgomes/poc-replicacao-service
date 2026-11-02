#!/usr/bin/env python3
"""Midpoint/half-RTT clock evidence after the SSH handshake, using the least RTT of five samples."""
import select
import subprocess
import time


def monotonic_ns():
    # CLOCK_MONOTONIC is frequency-adjusted on Linux; RAW also excludes WSL/NTP slewing.
    return time.clock_gettime_ns(time.CLOCK_MONOTONIC_RAW)


def clock(host):
    process = subprocess.Popen(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host,
        "# POC_CLOCK\nprintf 'ready\\n'; while read -r sample; do date +%s%N; done"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    def line():
        if not select.select([process.stdout], [], [], 20)[0]:
            raise TimeoutError("clock response timeout")
        return process.stdout.readline().strip()
    try:
        if line() != b"ready":
            raise RuntimeError("clock handshake failed")
        samples = []
        for _ in range(5):
            start = monotonic_ns()
            process.stdin.write(b"sample\n")
            process.stdin.flush()
            timestamp = int(line())
            end = monotonic_ns()
            rtt = end - start
            # Compare the remote clocks against one monotonic anchor. WSL wall-clock steps
            # between the two SSH sessions must not become fictitious inter-host skew.
            samples.append((rtt, timestamp - (start + (end - start) // 2)))
        if not samples:
            raise RuntimeError("unstable local clock")
        rtt, offset = min(samples)
        return offset, rtt // 2
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        process.stdout.close()


if __name__ == "__main__":
    a, uncertainty_a = clock("desenv-server")
    b, uncertainty_b = clock("infra-server")
    print(a - b, uncertainty_a + uncertainty_b)
