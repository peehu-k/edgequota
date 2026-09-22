"""
Fair, real, reproducible latency comparison: a Redis-backed centralized
rate-limit check (the "obvious" approach EdgeQuota argues against) vs. a
local in-memory Count-Min-Sketch-backed check (what EdgeQuota actually
does), on the SAME machine, over the SAME loopback interface, so the only
variable being measured is "does this request need a round trip to a
shared store, or not."

Honesty notes (read before quoting these numbers anywhere):
  - This runs the real redis-server binary (via redislite), not a mock.
  - It's over TCP loopback (127.0.0.1), zero real network latency. A real
    multi-region deployment would see this Redis-path number get much
    worse (tens of ms of real network RTT) while the local-check number
    stays the same regardless of topology -- so this loopback comparison
    is the *most generous possible case for Redis* and EdgeQuota's actual
    advantage in a real multi-region deployment would be larger, not
    smaller, than what's measured here.
  - The Redis check uses a single atomic Lua EVAL (GET + conditional
    INCRBY), which is the correct, realistic way to implement a check-
    and-increment rate limiter in Redis (not a naive GET-then-SET, which
    would have a race condition).
  - The "local" side re-implements the same Count-Min Sketch logic used
    in the actual Java EdgeQuota code (see docs/prototypes/cms_bench.py),
    in Python, so both sides of the comparison are measured in the same
    language/runtime for fairness. The real Java implementation is
    expected to be faster than this Python number, not slower, so this
    is a conservative (favorable to Redis) comparison on that axis too.
"""
import time
import statistics
import redislite
import redis

N = 20000
WARMUP = 2000

# ---- Redis-backed centralized check ----
r_embedded = redislite.Redis(serverconfig={'port': '16399'})
rc = redis.Redis(host='127.0.0.1', port=16399)

lua_check_and_incr = """
local current = tonumber(redis.call('GET', KEYS[1]) or "0")
local weight = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if current + weight > limit then
  return 0
else
  redis.call('INCRBY', KEYS[1], weight)
  return 1
end
"""
script = rc.register_script(lua_check_and_incr)
rc.set("tenant:acme", 0)

def redis_check():
    return script(keys=["tenant:acme"], args=[1, 10_000_000])

for _ in range(WARMUP):
    redis_check()

redis_latencies = []
start_wall = time.perf_counter()
for _ in range(N):
    t0 = time.perf_counter()
    redis_check()
    redis_latencies.append((time.perf_counter() - t0) * 1e6)  # microseconds
redis_wall = time.perf_counter() - start_wall

# ---- Local Count-Min Sketch check (mirrors the real Java CountMinSketch) ----
import math

class CMS:
    def __init__(self, epsilon, delta, seed):
        self.width = math.ceil(math.e / epsilon)
        self.depth = math.ceil(math.log(1 / delta))
        self.seed = seed
        self.table = [[0.0] * self.width for _ in range(self.depth)]

    def idx(self, key):
        h1 = hash((key, self.seed, 1))
        h2 = hash((key, self.seed, 2)) | 1
        return [(h1 + i * h2) % self.width for i in range(self.depth)]

    def estimate(self, key):
        return min(self.table[row][col] for row, col in enumerate(self.idx(key)))

    def update(self, key, w):
        for row, col in enumerate(self.idx(key)):
            self.table[row][col] += w

sketch = CMS(0.001, 0.01, 42)
LIMIT = 10_000_000

def local_check():
    current = sketch.estimate("tenant:acme")
    if current + 1 > LIMIT:
        return 0
    sketch.update("tenant:acme", 1)
    return 1

for _ in range(WARMUP):
    local_check()

local_latencies = []
start_wall = time.perf_counter()
for _ in range(N):
    t0 = time.perf_counter()
    local_check()
    local_latencies.append((time.perf_counter() - t0) * 1e6)
local_wall = time.perf_counter() - start_wall

def pct(data, p):
    data = sorted(data)
    k = int(len(data) * p)
    return data[min(k, len(data) - 1)]

print(f"N={N} requests, each path measured individually (microseconds)\n")
print(f"{'':20}{'mean':>10}{'median':>10}{'p95':>10}{'p99':>10}{'throughput (req/s)':>22}")
print(f"{'Redis (TCP, local)':20}"
      f"{statistics.mean(redis_latencies):10.1f}"
      f"{statistics.median(redis_latencies):10.1f}"
      f"{pct(redis_latencies,0.95):10.1f}"
      f"{pct(redis_latencies,0.99):10.1f}"
      f"{N/redis_wall:22.0f}")
print(f"{'EdgeQuota (local CMS)':20}"
      f"{statistics.mean(local_latencies):10.1f}"
      f"{statistics.median(local_latencies):10.1f}"
      f"{pct(local_latencies,0.95):10.1f}"
      f"{pct(local_latencies,0.99):10.1f}"
      f"{N/local_wall:22.0f}")

speedup = statistics.mean(redis_latencies) / statistics.mean(local_latencies)
print(f"\nlocal check is ~{speedup:.1f}x faster than the Redis round trip, on loopback with zero real network latency")

r_embedded.shutdown(nosave=True)

