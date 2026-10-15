# Results: SRE

This page preserves the WAN/SRE benchmark summary tracked in [`results/SRE-results.md`](../results/SRE-results.md). Timeout per variant: `14400s`.

These results come from the network-verification research workflow, not from the default Maven build path. Keep that in mind when comparing them with the easy-to-rerun NQueens results.

## What To Look For

- how `NDD` compares with the original `NDD-Origin` baseline at increasing fattree sizes
- whether memory reduction tracks runtime reduction
- how much BDD-node growth is cut by the field-aware reuse strategy
- where the plain BDD baseline times out but NDD variants still finish

## Selected Highlights

| Dataset | Metric | NDD-Origin | NDD | Improvement |
| --- | --- | ---: | ---: | ---: |
| `bgp_fattree08`, `MF=3` | total time (s) | 60.828954399 | 25.602449596 | 2.38x faster |
| `bgp_fattree08`, `MF=3` | peak RSS (MB) | 4220.4 | 2046.0 | 51.5% lower |
| `bgp_fattree08`, `MF=3` | BDD nodes | 38199434 | 3208829 | 91.6% fewer |
| `bgp_fattree12`, `MF=3` | total time (s) | 636.086437962 | 230.905827854 | 2.75x faster |
| `bgp_fattree12`, `MF=3` | peak RSS (MB) | 26274.3 | 18113.7 | 31.1% lower |
| `bgp_fattree12`, `MF=3` | BDD nodes | 645702063 | 61386922 | 90.5% fewer |
| `bgp_fattree16`, `MF=2` | total time (s) | 1178.287171806 | 472.056353484 | 2.50x faster |

## Reading The Table

- `dataset` identifies the network family and size
- `MF` is the harness parameter preserved from the original evaluation
- `src(s)` is a source or preprocessing stage reported by the experiment
- `total(s)` is the end-to-end runtime
- `peak rss` is peak resident memory
- `bgp routes` gives a rough sense of scale
- `bdd nodes` reflects the size of the BDD label layer used during the run

## Full Table

The full per-dataset tables are in [`results/SRE-results.md`](../results/SRE-results.md). That file
names the variants differently: `ndd` is `NDD-Origin`, `ndd-reuse` is `NDD-Reuse`, and `ndd-array`
is `NDD` (see [`results/README.md`](../results/README.md)).
