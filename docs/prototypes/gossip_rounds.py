import random, math

def simulate(n, fanout_fn, trials=200, seed=0):
    rng = random.Random(seed)
    rounds_needed = []
    for t in range(trials):
        informed = {0}
        rounds = 0
        while len(informed) < n and rounds < 100:
            fanout = fanout_fn(n)
            new_informed = set(informed)
            for node in list(informed):
                targets = rng.sample(range(n), min(fanout, n))
                for tgt in targets:
                    new_informed.add(tgt)
            informed = new_informed
            rounds += 1
        rounds_needed.append(rounds)
    return sum(rounds_needed)/len(rounds_needed), max(rounds_needed)

def fanout_log2(n):
    return max(1, math.ceil(math.log2(n)))

print(f"{'n':>5} {'avg_rounds':>12} {'max_rounds':>12} {'log2(n)':>10}")
for n in [2,4,8,16,32,64,128,256]:
    avg, mx = simulate(n, fanout_log2)
    print(f"{n:5d} {avg:12.2f} {mx:12d} {math.log2(n):10.2f}")
