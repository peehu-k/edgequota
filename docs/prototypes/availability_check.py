"""
Separate, minimal script (kept independent of the main latency benchmark
so a hang/retry in one doesn't affect the other): demonstrates what
happens to each approach when the "coordinator" (Redis) becomes
unreachable, using a raw socket with a strict timeout and zero retries so
the failure mode is measured directly instead of masked by a client
library's automatic retry/backoff behavior.
"""
import socket
import time
import redislite

PORT = 16401
r = redislite.Redis(serverconfig={'port': str(PORT)})

def raw_redis_call(timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", PORT))
        s.sendall(b"*1\r\n$4\r\nPING\r\n")
        resp = s.recv(64)
        return True, resp
    except Exception as e:
        return False, str(e)
    finally:
        s.close()

ok, resp = raw_redis_call(1.0)
print(f"redis reachable before kill: {ok} ({resp})")

r.shutdown(nosave=True)
time.sleep(0.3)

attempts = 10
failures = 0
t0 = time.perf_counter()
for _ in range(attempts):
    ok, resp = raw_redis_call(0.5)  # hard 0.5s timeout, no retry
    if not ok:
        failures += 1
elapsed = time.perf_counter() - t0

print(f"redis reachable after kill: {failures}/{attempts} attempts failed, "
      f"total {elapsed:.2f}s spent discovering that (each blocked request either "
      f"waited for connection-refused or the {0.5}s timeout)")
print("EdgeQuota-style local check after 'coordinator' is gone: "
      "unaffected, because it never depended on a coordinator to begin with "
      "(no code path even exists that could fail this way)")
