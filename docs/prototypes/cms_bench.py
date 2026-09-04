import random, math

def murmur_like_hash(key, seed):
    # not real murmur3, just a stand-in hash for the python validation prototype
    h = hash((key, seed))
    return h

class CMS:
    def __init__(self, epsilon, delta, seed):
        self.width = math.ceil(math.e / epsilon)
        self.depth = math.ceil(math.log(1/delta))
        self.seed = seed
        self.table = [[0.0]*self.width for _ in range(self.depth)]
        self.total = 0.0

    def idx(self, key):
        h1 = hash((key, self.seed, 1))
        h2 = hash((key, self.seed, 2)) | 1
        return [ (h1 + i*h2) % self.width for i in range(self.depth) ]

    def update(self, key, w):
        for row, col in enumerate(self.idx(key)):
            self.table[row][col] += w
        self.total += w

    def estimate(self, key):
        return min(self.table[row][col] for row, col in enumerate(self.idx(key)))

random.seed(7)
sketch = CMS(0.001, 0.01, 42)
num_keys = 2000
truth = {}
total_events = 500_000
total_weight = 0
for i in range(total_events):
    if random.random() < 0.8:
        key = f"hot-{random.randrange(int(num_keys*0.2))}"
    else:
        key = f"cold-{random.randrange(int(num_keys*0.8))}"
    w = 1 + random.randrange(50)
    sketch.update(key, w)
    truth[key] = truth.get(key, 0) + w
    total_weight += w

theoretical_bound = 0.001 * total_weight
max_err = 0
sum_abs_err = 0
violations = 0
for k, v in truth.items():
    est = sketch.estimate(k)
    err = est - v
    max_err = max(max_err, err)
    sum_abs_err += abs(err)
    if err > theoretical_bound:
        violations += 1

print(f"width={sketch.width} depth={sketch.depth} totalWeight={total_weight}")
print(f"theoreticalBound(eps*W)={theoretical_bound:.1f}")
print(f"distinctKeys={len(truth)} maxObservedError={max_err:.2f} meanAbsError={sum_abs_err/len(truth):.3f}")
print(f"violationRate={violations/len(truth)*100:.3f}% (delta=0.01 permits up to ~1% in theory)")
