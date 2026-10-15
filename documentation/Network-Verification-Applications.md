# Network Verification Applications

NDD was introduced for network verification, and this repository still reflects that origin.

The basic idea is straightforward: packet-processing programs naturally reason over fields such as destination IP, source IP, protocol, ports, flags, and rewritten header values. NDD keeps those field boundaries explicit, which often reduces label redundancy compared with a pure bit-level BDD encoding.

## Why NDD Fits Network Workloads

Compared with a plain BDD encoding, a field-aware encoding is attractive when:

- matches are prefix-based or interval-based inside a header field
- different rules touch different subsets of fields
- many paths share common suffixes inside a field domain
- you want the model structure to mirror packet semantics instead of raw bit positions

That is exactly the setting of forwarding, reachability, ACL, and packet-transformation analysis.

## What Exists In This Repository

### 1. WAN / SRE Research Drivers

The repository still contains the original WAN verification experiment code under:

- [`src/main/java/application/wan/bdd/`](../src/main/java/application/wan/bdd)
- [`src/main/java/application/wan/ndd/`](../src/main/java/application/wan/ndd)

Important entry points:

- BDD baseline: [`application.wan.bdd.exp.EvalDataplaneVerifier`](../src/main/java/application/wan/bdd/exp/EvalDataplaneVerifier.java)
- NDD path: [`application.wan.ndd.exp.EvalDataplaneVerifierNDDAP`](../src/main/java/application/wan/ndd/exp/EvalDataplaneVerifierNDDAP.java)

These drivers are research artifacts rather than polished library examples:

- they are excluded from the default Maven build
- they expect external datasets on fixed filesystem paths such as `/data/zcli-data/network-decision-diagram/datasets/wan/...`
- they assume the surrounding experiment environment used in the original evaluation

Even so, they are useful as documentation for how NDD was applied to incremental dataplane verification.

### 2. Batfish Integration Sketch

The directory [`src/main/java/application/batfish/`](../src/main/java/application/batfish) contains a lightweight integration note plus [`batfish.patch`](../src/main/java/application/batfish/batfish.patch).

That patch targets:

- Batfish commit `b89d48eb6a580ae4124c2c3e8e7e06fad8583491`
- Pybatfish commit `5a0488d1574534e425b3e70df5cc65171fb285a2`

The patch shows the key migration steps:

- replace the default Java BDD factory with `NDDFactory`
- declare packet-header domains up front with `setVarNum(int[] fieldWidths, int nddTableSize)`
- keep primed and unprimed packet fields separated in a way that preserves the intended pairings

The repository note says this path was used for "all-pair reachability (under k failure)" checks.

## Modeling Guidance For Network Headers

When adapting NDD to a verifier or symbolic packet engine:

1. Split the packet schema into semantic fields, not one flat bitvector.
2. Keep repeated domain types separate when they play different roles.
3. Declare the full field layout before creating symbolic variables.
4. Expect the biggest wins when many rules share field-local structure.

Typical field partition:

- source IP
- destination IP
- source port
- destination port
- IP protocol
- TCP flags
- ACL tags, rewrite metadata, or other logical state

The Batfish patch is a good concrete example because it enumerates source and destination addresses, primed copies, ports, protocol, ICMP fields, TCP flags, DSCP, ECN, fragment offset, and packet length as explicit domains.

## Relationship To The Result Pages

- [Results: SRE](Results-SRE.md) preserves the full WAN/SRE benchmark tables derived from the research drivers.
- [Benchmarks](Benchmarks.md) explains how to read those results and how they differ from the maintained NQueens example path.
- [Parameters](Parameters.md) explains why up-front field declaration matters for these applications.

## Practical Caveat

If your goal is to embed NDD in a new verifier today, treat the WAN and Batfish paths in this repository as reference material, not as drop-in examples. The maintained, buildable path in this repository is the core library plus the NQueens-style examples; the network-verification integrations still require external datasets, environment setup, or upstream adaptation work.
