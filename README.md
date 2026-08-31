# EdgeQuota

Decentralized, cost-aware rate limiter for multi-region API gateways.

Per-tenant quotas are enforced with no coordinator on the request path:
each node keeps a local Count-Min Sketch of observed cost, and nodes
gossip incremental deltas to each other to stay approximately in sync on
cluster-wide usage.

Work in progress -- core sketch implementation first, gossip and quota
layers to follow.
