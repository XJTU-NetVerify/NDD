#ifndef PNDD_MC_H
#define PNDD_MC_H
#include "pndd.h"
#ifdef __cplusplus
extern "C" {
#endif

/* Boolean state-set layer. One active domain/engine per process. Objects must
 * be used by one caller at a time; enumeration callbacks may reenter this API.
 * BCDD only, 1..16 logical bits per slot (default 16), slots numbered from 0.
 * Labels reserve current bit i at 2*i, next at 2*i+1, scope at 2*value_bits.
 * Bit i has numeric weight 2^i. No physical handles escape this interface. */
typedef struct pndd_mc_domain pndd_mc_domain;
typedef struct pndd_mc_set pndd_mc_set;
typedef struct pndd_mc_rel pndd_mc_rel;
typedef struct {
    pndd_config engine;
    uint32_t value_bits;
    pndd_backend backend;
} pndd_mc_config;
pndd_mc_config pndd_mc_default_config(void);
pndd_mc_domain *pndd_mc_open(const pndd_mc_config *, size_t slots);
/* Refuses close while sets, relations or enumeration snapshots exist. */
int pndd_mc_close(pndd_mc_domain *);
const char *pndd_mc_error(const pndd_mc_domain *); /* NULL: last open error */
int pndd_mc_collect(pndd_mc_domain *);
pndd_stats pndd_mc_stats(pndd_mc_domain *);

/* k=-1: full projection (proj must be NULL); k=0: epsilon projection.
 * Otherwise proj has k strictly increasing, in-range slots. Copied at create.
 * Empty projection has exactly two sets: empty and {()}.
 * The domain outlives its sets. Destroyed pointers must never be reused. */
pndd_mc_set *pndd_mc_set_create(pndd_mc_domain *, int k, const uint32_t *proj);
void pndd_mc_set_destroy(pndd_mc_set *);
size_t pndd_mc_set_arity(const pndd_mc_set *);
int pndd_mc_set_clear(pndd_mc_set *);
int pndd_mc_set_fill(pndd_mc_set *); /* full logical projection universe */
int pndd_mc_set_copy(pndd_mc_set *dst, const pndd_mc_set *src);
int pndd_mc_set_empty(const pndd_mc_set *); /* 0/1, -1 on error */
int pndd_mc_set_equal(const pndd_mc_set *, const pndd_mc_set *);
/* Tuples are in projection order, exactly arity entries; NULL iff arity=0.
 * Negative and out-of-range values fail, never truncate. Mutations return
 * 1/0. On core/validation failure the previous destination roots survive. */
int pndd_mc_set_add(pndd_mc_set *, const int *tuple);
int pndd_mc_set_member(const pndd_mc_set *, const int *tuple); /* 0/1/-1 */
int pndd_mc_set_union(pndd_mc_set *dst, const pndd_mc_set *src);
int pndd_mc_set_intersect(pndd_mc_set *dst, const pndd_mc_set *src);
int pndd_mc_set_minus(pndd_mc_set *dst, const pndd_mc_set *src);
/* Simultaneously dst := old_dst UNION old_src;
 * src := old_src MINUS old_dst. Same projection, distinct objects. */
int pndd_mc_set_zip(pndd_mc_set *dst, pndd_mc_set *src);
int pndd_mc_set_project(pndd_mc_set *dst, const pndd_mc_set *src);
int pndd_mc_set_project_minus(pndd_mc_set *dst, const pndd_mc_set *src,
                            const pndd_mc_set *minus);
/* dst projection must be exactly the union of input projections. */
int pndd_mc_set_join(pndd_mc_set *dst, const pndd_mc_set *, const pndd_mc_set *);
double pndd_mc_set_count(const pndd_mc_set *); /* NAN on error; double precision, may overflow to INF */
/* Distinct reachable nonterminal outer NDD nodes; excludes label BDD nodes.
 * Returns 1/0 and leaves output unchanged on failure. */
int pndd_mc_set_node_count(const pndd_mc_set *, uint64_t *nodes);

/* Enumerates a retained snapshot, without holding admission during callbacks.
 * Callback: positive=continue, 0=stop, negative=error. enum returns 1=complete,
 * 0=stopped, -1=error. Tuples are borrowed until callback return; order is not
 * specified. Callbacks may mutate sets and collect, even clear/destroy the
 * enumerated set; the domain stays open until enumeration returns.
 * Enumeration of a large universe is intentionally output-sensitive. */
typedef int (*pndd_mc_visit)(void *, const int *tuple, size_t arity);
int pndd_mc_set_enum(const pndd_mc_set *, pndd_mc_visit, void *context);
int pndd_mc_set_example(const pndd_mc_set *, int *tuple); /* 1=found, 0=empty, -1=error */
/* LTSmin-style update: visit the source snapshot and let the callback add to
 * dst. Same domain, projections may differ. Callback must keep dst alive.
 * Callback failure stops immediately; prior callback mutations are retained. */
typedef int (*pndd_mc_update_cb)(pndd_mc_set *dst, void *, const int *, size_t);
int pndd_mc_set_update(pndd_mc_set *dst, const pndd_mc_set *src,
                       pndd_mc_update_cb, void *context);

/* Relations: projection syntax is the same as set_create. create uses the
 * same read/write projection; create_rw stores their union as outer fields.
 * Omitted slots mean identity, not unconstrained current/next pairs. */
pndd_mc_rel *pndd_mc_rel_create(pndd_mc_domain *, int k, const uint32_t *proj);
pndd_mc_rel *pndd_mc_rel_create_rw(pndd_mc_domain *, int read_k, const uint32_t *read_proj,
                                 int write_k, const uint32_t *write_proj);
void pndd_mc_rel_destroy(pndd_mc_rel *);
int pndd_mc_rel_clear(pndd_mc_rel *);
int pndd_mc_rel_empty(const pndd_mc_rel *); /* 0/1/-1 */
int pndd_mc_rel_copy(pndd_mc_rel *dst, const pndd_mc_rel *src);
int pndd_mc_rel_equal(const pndd_mc_rel *, const pndd_mc_rel *); /* layout-sensitive, 0/1/-1 */
size_t pndd_mc_rel_read_arity(const pndd_mc_rel *);
size_t pndd_mc_rel_write_arity(const pndd_mc_rel *);
/* src contains read-arity values; dst contains write-arity values. All supplied
 * values must be in range, including ignored dst values in copy positions.
 * A nonzero copy[i] means identity only for write-only slots; read/write slots
 * ignore that flag and store the exact supplied pair (legacy adapter contract).
 * Read-only slots fix current=src and next=current. Write-only without copy
 * fixes next=dst and leaves current free. NULL copy is all zero.
 * Results are unions of pairs, so duplicate/overlapping insertions deduplicate.
 * On failure the old root survives. No deferred insertion/flush state. */
int pndd_mc_rel_add(pndd_mc_rel *, const int *src, const int *dst);
int pndd_mc_rel_add_cpy(pndd_mc_rel *, const int *src, const int *dst, const int *copy);
/* Diagnostic membership takes FULL domain-sized current and next vectors,
 * including omitted slots, whose identity constraint is checked explicitly.
 * Returns 0/1/-1. Zero-dimensional vectors may be NULL. */
int pndd_mc_rel_member(const pndd_mc_rel *, const int *current, const int *next);
/* Count distinct current/next pairs over the UNION projection, excluding scope
 * and omitted identity dimensions. Empty=0; epsilon relation=1. A write-only
 * insertion/copy represents 2^value_bits pairs, not one insertion event.
 * Thus this count is not the number of full-domain pairs accepted by member.
 * Double precision; NAN on error, INF on overflow. */
double pndd_mc_rel_count(const pndd_mc_rel *);
int pndd_mc_rel_node_count(const pndd_mc_rel *, uint64_t *nodes);
/* Source projection must equal the read projection. Uses the set snapshot
 * callback contract; keep relation alive during callbacks. On callback error,
 * previous successful insertions remain. No atomic batch/rollback promise. */
typedef int (*pndd_mc_rel_update_cb)(pndd_mc_rel *, void *, const int *, size_t);
int pndd_mc_rel_update(pndd_mc_rel *, const pndd_mc_set *, pndd_mc_rel_update_cb, void *);

/* Forward image: dst := {y | exists x in src: relation(x,y)}.
 * Same domain and identical src/dst projection; the relation's union
 * projection must be a subset of that projection. Omitted relation fields
 * preserve their source values. Supports dst==src. Returns 1/0 and preserves
 * the previous destination root on failure. One admission and one external
 * Lace RUN, including any scheduled operation-boundary collection.
 * This is a core set_next API, not an LTSmin backend registration. */
int pndd_mc_set_next(pndd_mc_set *dst, const pndd_mc_set *src, const pndd_mc_rel *relation);
#ifdef __cplusplus
}
#endif
#endif
