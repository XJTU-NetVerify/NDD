/* Boolean network decision diagrams. The task/ownership design follows MTPNDD
 * (Augists / XJTU ANTS Netverify Lab); implementation and compact tables are new.
 * Sylvan/Lace remain separate Apache-2.0 dependencies. See native/NOTICE. */
#include "pndd.h"
#include <sylvan.h>
#ifdef PNDD_EXPERIMENT_SYLVAN_CACHE
#include <sylvan_int.h>
#endif
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <limits.h>
#ifdef PNDD_PROFILE
#include <stdio.h>
typedef struct {
    uint64_t labels_calls, labels_ns, label_hits, label_misses, prepare_ns, misses, jobs, costly_jobs, bins[5];
} Profile;
#endif

#define SHARDS 64
#define BLOCK 1024
#define BLOCKS 65536
#define INVALID_ID UINT32_MAX
#define MAX_FIELDS 256
#define LABEL_BITS UINT32_MAX

typedef struct { uint64_t label; uint32_t target; } Edge;
typedef struct Node {
    uint64_t hash;
    uint32_t field, count, next, cookie, refs;
    unsigned char mark;
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    /* Relative cardinality is immutable with this node. The admitted count
       path alone writes it; a newly allocated/reused node starts invalid. */
    double sat_count;
    unsigned char sat_count_valid;
#endif
    Edge edges[];
} Node;
typedef struct { uint64_t low, high, hash; uint32_t var, next; unsigned char mark; } Label;
/* Independent shards must not invalidate each other's lock cache line. */
typedef struct { _Alignas(64) atomic_flag flag; } SpinLock;
static void spin_lock(SpinLock *lock) {
    while (atomic_flag_test_and_set_explicit(&lock->flag, memory_order_acquire)) {
#if defined(__i386__) || defined(__x86_64__)
        __builtin_ia32_pause();
#else
        atomic_signal_fence(memory_order_seq_cst);
#endif
    }
}
static void spin_unlock(SpinLock *lock) { atomic_flag_clear_explicit(&lock->flag, memory_order_release); }
typedef struct { SpinLock lock; uint32_t *buckets; size_t size, count; } Unique;
typedef struct { uint64_t a, b, result; uint32_t key; } Cache;
typedef struct RootFrame {
    struct RootFrame *previous;
    uint64_t *values;
    size_t count, stride;
    uint32_t field;
} RootFrame;
typedef struct {
    pndd_field spec;
    uint32_t start;
    uint64_t universe;
    uint32_t *literals;
} Field;
typedef struct { uint64_t label; uint32_t a, b, target; } Job;
typedef struct { _Alignas(64) uint64_t hits, misses, nodes_live, nodes_created, labels_live, labels_created, merge_ns; size_t credit; uint32_t node_next, node_end, cookie_next; char padding[180];
#ifdef PNDD_PROFILE
    Profile profile;
#endif
} WorkerStats;
struct pndd_engine {
    pndd_config config;
    size_t field_count;
    Field fields[MAX_FIELDS];
    uint32_t widths[4];
    int sylvan, pending_gc;
    _Atomic(Node **) node_blocks[BLOCKS];
    _Atomic(Label **) label_blocks[BLOCKS];
    uint32_t node_high, label_high;
    uint32_t *free_nodes, *free_labels;
    size_t free_node_count, free_label_count;
    Unique node_unique[SHARDS], label_unique[SHARDS];
    pthread_mutex_t allocation;
    SpinLock cache_locks[SHARDS];
    Cache *cache;
    RootFrame **roots;
    _Atomic int failure;
    _Atomic uint64_t bytes, peak, nodes_live, nodes_created, labels_live, labels_created;
    _Atomic uint64_t tasks, stolen, hits, misses, collections, runs, merge_ns, label_collections;
    uint64_t build_ns, reduce_ns, count_ns;
    size_t allocation_limit, hard_limit;
    WorkerStats worker_stats[256];
    uint64_t admissions, last_gc_bytes;
#ifdef PNDD_EXPERIMENT_SYLVAN_CACHE
    uint64_t label_cache_opid;
#endif
#ifdef PNDD_PROFILE
    uint64_t profile_calls[8], profile_ns[8];
#endif
};
static pndd_engine *G;
void pndd_sylvan_node_created(void) { if (G) atomic_fetch_add(&G->labels_created, 1); }
static pthread_mutex_t admission = PTHREAD_MUTEX_INITIALIZER;
static _Atomic uint64_t cookies = 1;
static const char *open_error = "No active native engine";

static uint64_t now_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000 + (uint64_t)t.tv_nsec;
}
#ifdef PNDD_PROFILE
/* API times are inclusive; label/prepare sums are worker wall-time, not additive
   wall-time shares in parallel runs. They exclude close but include checks. */
#define PROFILE_START uint64_t profile_start = now_ns()
#define PROFILE_END(kind) do { if (G) { ++G->profile_calls[kind]; G->profile_ns[kind] += now_ns() - profile_start; } } while (0)
static void profile_dump(void) {
    Profile p = {0};
    for (unsigned i = 0; i < G->config.workers; ++i) {
        Profile *w = &G->worker_stats[i].profile;
        p.labels_calls += w->labels_calls; p.labels_ns += w->labels_ns;
        p.label_hits += w->label_hits; p.label_misses += w->label_misses;
        p.prepare_ns += w->prepare_ns; p.misses += w->misses;
        p.jobs += w->jobs; p.costly_jobs += w->costly_jobs;
        for (int j = 0; j < 5; ++j) p.bins[j] += w->bins[j];
    }
    const char *names[] = {"apply", "count", "snapshot", "reachable", "gc", "mincost", "exist", "evaluate"};
    for (int i = 0; i < 8; ++i)
        fprintf(stderr, "profile_%s_calls:%llu\nprofile_%s_seconds:%.9f\n", names[i], (unsigned long long)G->profile_calls[i], names[i], G->profile_ns[i] / 1e9);
    fprintf(stderr, "profile_label_calls:%llu\nprofile_label_worker_seconds:%.9f\nprofile_prepare_worker_seconds:%.9f\nprofile_ndd_misses:%llu\nprofile_jobs:%llu\nprofile_costly_jobs:%llu\n", (unsigned long long)p.labels_calls, p.labels_ns / 1e9, p.prepare_ns / 1e9, (unsigned long long)p.misses, (unsigned long long)p.jobs, (unsigned long long)p.costly_jobs);
    for (int i = 0; i < 5; ++i) fprintf(stderr, "profile_job_bin_%d:%llu\n", i, (unsigned long long)p.bins[i]);
    fprintf(stderr, "profile_label_cache_hits:%llu\nprofile_label_cache_misses:%llu\n", (unsigned long long)p.label_hits, (unsigned long long)p.label_misses);
}
#else
#define PROFILE_START ((void)0)
#define PROFILE_END(kind) ((void)0)
#endif
static uint64_t mix(uint64_t x) {
    x ^= x >> 30; x *= UINT64_C(0xbf58476d1ce4e5b9);
    x ^= x >> 27; x *= UINT64_C(0x94d049bb133111eb);
    return x ^ (x >> 31);
}
static void fail(int code) { int zero = 0; atomic_compare_exchange_strong(&G->failure, &zero, code); }
static WorkerStats *worker_stats(void);
static void *allocate(size_t n) {
    if (!n) n = 1;
    if (n > SIZE_MAX - sizeof(size_t)) { fail(1); return NULL; }
    size_t total = n + sizeof(size_t);
    WorkerStats *stats = worker_stats();
    size_t reserved = stats ? (stats->credit >= total ? 0 : total > 32768 ? total : 32768) : total;
    if (reserved) {
        uint64_t before = atomic_fetch_add(&G->bytes, reserved);
        if (before > G->allocation_limit || reserved > G->allocation_limit - before) {
            atomic_fetch_sub(&G->bytes, reserved); fail(1); return NULL;
        }
        if (stats) stats->credit += reserved;
        uint64_t value = before + reserved, peak = atomic_load(&G->peak);
        while (value > peak && !atomic_compare_exchange_weak(&G->peak, &peak, value)) {}
    }
    size_t *p = calloc(1, total);
    if (!p) { if (!stats) atomic_fetch_sub(&G->bytes, reserved); fail(1); return NULL; }
    if (stats) stats->credit -= total;
    *p = total; return p + 1;
}
static void deallocate(void *p) {
    if (!p) return;
    size_t *q = (size_t *)p - 1; WorkerStats *stats = worker_stats();
    if (stats) {
        stats->credit += *q;
        if (stats->credit > 65536) { atomic_fetch_sub(&G->bytes, stats->credit - 32768); stats->credit = 32768; }
    } else atomic_fetch_sub(&G->bytes, *q);
    free(q);
}

static Node *node(uint32_t id) {
    Node **b = atomic_load_explicit(&G->node_blocks[id / BLOCK], memory_order_acquire);
    return b ? atomic_load_explicit((_Atomic(Node *) *)&b[id % BLOCK], memory_order_acquire) : NULL;
}
static Label *label(uint64_t id) {
    Label **b = atomic_load_explicit(&G->label_blocks[id / BLOCK], memory_order_acquire);
    return b ? b[id % BLOCK] : NULL;
}
static uint32_t node_field(uint32_t id) { return id < 2 ? (uint32_t)G->field_count : node(id)->field; }
static int reversed(uint32_t field) {
    return G->config.reverse_labels && G->fields[field].spec.backend <= PNDD_BCDD;
}
static uint32_t label_end(uint32_t field) {
    return reversed(field) ? G->fields[field].spec.width : G->widths[G->fields[field].spec.backend];
}
static void root_push(RootFrame *r, uint32_t field, void *values, size_t stride, size_t count) {
    unsigned worker = lace_get_worker()->worker;
    r->previous = G->roots[worker]; r->values = values;
    r->stride = stride; r->count = count; r->field = field;
    G->roots[worker] = r;
}
static void root_pop(RootFrame *r) { G->roots[lace_get_worker()->worker] = r->previous; }
static WorkerStats *worker_stats(void) {
    WorkerP *worker = lace_get_worker();
    return worker ? &G->worker_stats[worker->worker] : NULL;
}
static uint64_t cache_hash(uint32_t key, uint64_t a, uint64_t b) { return mix(a ^ mix(b) ^ ((uint64_t)key << 32)); }
static int pndd_cache_get(uint32_t key, uint64_t a, uint64_t b, uint64_t *result) {
    uint64_t h = cache_hash(key, a, b); size_t index = h & (G->config.cache_entries - 1);
    SpinLock *lock = &G->cache_locks[index % SHARDS];
    spin_lock(lock); Cache entry = G->cache[index]; spin_unlock(lock);
    int found = entry.key == key && entry.a == a && entry.b == b;
    WorkerStats *stats = worker_stats();
    if (found) { *result = entry.result; if (stats) ++stats->hits; else atomic_fetch_add(&G->hits, 1); }
    else { if (stats) ++stats->misses; else atomic_fetch_add(&G->misses, 1); }
    return found;
}
static void pndd_cache_put(uint32_t key, uint64_t a, uint64_t b, uint64_t result) {
    if (atomic_load(&G->failure) || result == PNDD_INVALID) return;
    uint64_t h = cache_hash(key, a, b); size_t index = h & (G->config.cache_entries - 1);
    SpinLock *lock = &G->cache_locks[index % SHARDS];
    spin_lock(lock); G->cache[index] = (Cache){a, b, result, key}; spin_unlock(lock);
}
static int label_cache_get(uint32_t mode, uint32_t op, uint64_t a, uint64_t b, uint64_t *result) {
    int found;
#ifdef PNDD_EXPERIMENT_SYLVAN_CACHE
    if (mode == PNDD_BCDD) {
        found = cache_get3(G->label_cache_opid, a, b, op, result);
        WorkerStats *stats = worker_stats();
        if (found) ++stats->hits; else ++stats->misses;
    } else
#endif
    found = pndd_cache_get(0x100 + mode * 16 + op, a, b, result);
#ifdef PNDD_PROFILE
    if (found) ++worker_stats()->profile.label_hits; else ++worker_stats()->profile.label_misses;
#endif
    return found;
}
static void label_cache_put(uint32_t mode, uint32_t op, uint64_t a, uint64_t b, uint64_t result) {
#ifdef PNDD_EXPERIMENT_SYLVAN_CACHE
    if (mode == PNDD_BCDD) {
        if (!atomic_load(&G->failure) && result != PNDD_INVALID)
            cache_put3(G->label_cache_opid, a, b, op, result);
        return;
    }
#endif
    pndd_cache_put(0x100 + mode * 16 + op, a, b, result);
}
/* Table growth is performed under its shard lock; published objects never move. */
static int grow_unique(Unique *u, int labels) {
    if (u->count < u->size * 2) return 1;
    uint32_t *buckets = allocate(u->size * 2 * sizeof(uint32_t));
    if (!buckets) return 0;
    for (size_t i = 0; i < u->size; ++i) {
        uint32_t id = u->buckets[i];
        while (id) {
            uint64_t h; uint32_t next;
            if (labels) { Label *l = label(id); h = l->hash; next = l->next; l->next = buckets[(h / SHARDS) & (u->size * 2 - 1)]; }
            else { Node *n = node(id); h = n->hash; next = n->next; n->next = buckets[(h / SHARDS) & (u->size * 2 - 1)]; }
            buckets[(h / SHARDS) & (u->size * 2 - 1)] = id; id = next;
        }
    }
    deallocate(u->buckets); u->buckets = buckets; u->size *= 2;
    return 1;
}
static uint32_t publish_node(Node *n) {
    WorkerStats *stats = worker_stats(); uint32_t id; uint64_t cookie;
    if (stats && stats->node_next < stats->node_end) {
        id = stats->node_next++; cookie = stats->cookie_next++;
    } else {
        pthread_mutex_lock(&G->allocation);
        if (G->free_node_count) {
            id = G->free_nodes[--G->free_node_count]; cookie = atomic_fetch_add(&cookies, 1);
        } else {
            unsigned take = stats ? 32 : 1; id = G->node_high;
            if (id > BLOCK * BLOCKS - take) { pthread_mutex_unlock(&G->allocation); fail(1); return INVALID_ID; }
            cookie = atomic_fetch_add(&cookies, take);
            if (cookie > UINT32_MAX - take) { pthread_mutex_unlock(&G->allocation); fail(1); return INVALID_ID; }
            for (uint32_t block = id / BLOCK; block <= (id + take - 1) / BLOCK; ++block) {
                if (!atomic_load(&G->node_blocks[block])) {
                    Node **data = allocate(BLOCK * sizeof(Node *));
                    if (!data) { pthread_mutex_unlock(&G->allocation); return INVALID_ID; }
                    atomic_store_explicit(&G->node_blocks[block], data, memory_order_release);
                }
            }
            G->node_high += take;
            if (stats) { stats->node_next = id + 1; stats->node_end = id + take; stats->cookie_next = (uint32_t)cookie + 1; }
        }
        pthread_mutex_unlock(&G->allocation);
    }
    if (cookie >= UINT32_MAX) { fail(1); return INVALID_ID; }
    n->cookie = (uint32_t)cookie;
    Node **block = atomic_load_explicit(&G->node_blocks[id / BLOCK], memory_order_acquire);
    atomic_store_explicit((_Atomic(Node *) *)&block[id % BLOCK], n, memory_order_release);
    if (stats) { ++stats->nodes_live; ++stats->nodes_created; }
    else { atomic_fetch_add(&G->nodes_live, 1); atomic_fetch_add(&G->nodes_created, 1); }
    return id;
}

static uint64_t plain_make(uint32_t var, uint64_t low, uint64_t high) {
    if (atomic_load(&G->failure) || (var != LABEL_BITS && (low == PNDD_INVALID || high == PNDD_INVALID))) return PNDD_INVALID;
    if (var != LABEL_BITS && low == high) return low;
    if (var == LABEL_BITS && !(low | high)) return 0;
    uint64_t h = mix(low ^ mix(high) ^ ((uint64_t)var << 32));
    Unique *u = &G->label_unique[h % SHARDS]; spin_lock(&u->lock);
    size_t bucket = (h / SHARDS) & (u->size - 1);
    for (uint32_t id = u->buckets[bucket]; id; id = label(id)->next) {
        Label *l = label(id);
        if (l->var == var && l->low == low && l->high == high) { spin_unlock(&u->lock); return id; }
    }
    if (!grow_unique(u, 1)) { spin_unlock(&u->lock); return PNDD_INVALID; }
    bucket = (h / SHARDS) & (u->size - 1);
    Label *l = allocate(sizeof(Label));
    if (!l) { spin_unlock(&u->lock); return PNDD_INVALID; }
    *l = (Label){low, high, h, var, u->buckets[bucket], 0};
    pthread_mutex_lock(&G->allocation);
    uint32_t id = G->free_label_count ? G->free_labels[--G->free_label_count] : G->label_high;
    Label **block = id < BLOCK * BLOCKS ? atomic_load(&G->label_blocks[id / BLOCK]) : NULL;
    if (!block && id < BLOCK * BLOCKS) {
        block = allocate(BLOCK * sizeof(Label *));
        if (block) atomic_store_explicit(&G->label_blocks[id / BLOCK], block, memory_order_release);
    }
    if (!block) { fail(1); pthread_mutex_unlock(&G->allocation); deallocate(l); spin_unlock(&u->lock); return PNDD_INVALID; }
    block[id % BLOCK] = l; if (id == G->label_high) ++G->label_high;
    pthread_mutex_unlock(&G->allocation);
    u->buckets[bucket] = id; ++u->count; spin_unlock(&u->lock);
    WorkerStats *stats = worker_stats();
    if (stats) { ++stats->labels_live; ++stats->labels_created; }
    else { atomic_fetch_add(&G->labels_live, 1); atomic_fetch_add(&G->labels_created, 1); }
    return id;
}
static uint64_t truth(uint32_t field) {
    return G->fields[field].spec.backend == PNDD_BCDD ? sylvan_true : 1;
}
static uint32_t label_var(uint32_t field, uint64_t value) {
    if (value == 0 || value == truth(field)) return UINT32_MAX;
    switch (G->fields[field].spec.backend) {
    case PNDD_BDD: return label(value)->var;
    case PNDD_BCDD: return sylvan_var(value);
    case PNDD_ZDD: return zdd_getvar(value);
    default: return UINT32_MAX;
    }
}
static uint64_t branch(uint32_t field, uint64_t value, uint32_t var, int high) {
    if (label_var(field, value) != var) return high && G->fields[field].spec.backend == PNDD_ZDD ? 0 : value;
    switch (G->fields[field].spec.backend) {
    case PNDD_BDD: return high ? label(value)->high : label(value)->low;
    case PNDD_BCDD: return high ? sylvan_high(value) : sylvan_low(value);
    default: return high ? zdd_gethigh(value) : zdd_getlow(value);
    }
}
static uint64_t make_label(uint32_t field, uint32_t var, uint64_t low, uint64_t high) {
    if (low == PNDD_INVALID || high == PNDD_INVALID || atomic_load(&G->failure)) return PNDD_INVALID;
    uint64_t result;
    switch (G->fields[field].spec.backend) {
    case PNDD_BDD: return plain_make(var, low, high);
    case PNDD_BCDD: result = sylvan_makenode(var, low, high); break;
    default: result = zdd_makenode(var, low, high); break;
    }
    if (result == PNDD_INVALID) fail(1);
    return result;
}
TASK_DECL_6(uint64_t, label_apply, uint32_t, uint32_t, uint64_t, uint64_t, uint32_t, uint32_t);
TASK_IMPL_6(uint64_t, label_apply, uint32_t, field, uint32_t, op, uint64_t, a, uint64_t, b, uint32_t, depth, uint32_t, owner) {
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || a == PNDD_INVALID || b == PNDD_INVALID) return 0;
    pndd_backend mode = G->fields[field].spec.backend;
    if (op != PNDD_DIFF && a > b) { uint64_t t = a; a = b; b = t; }
    if (op == PNDD_AND && (!a || !b)) return 0;
    if (op == PNDD_OR && !a) return b;
    if (op == PNDD_OR && !b) return a;
    if (op == PNDD_DIFF && (!a || a == b)) return 0;
    if (op == PNDD_DIFF && !b) return a;
    if (a == b) return a;
#ifdef PNDD_EXPERIMENT_BCDD_AND
    if (mode == PNDD_BCDD && a == (b ^ sylvan_complement))
        return op == PNDD_AND ? 0 : op == PNDD_OR ? truth(field) : a;
#endif
    if (mode == PNDD_BITSET) {
        uint64_t al = a ? label(a)->low : 0, ah = a ? label(a)->high : 0;
        uint64_t bl = b ? label(b)->low : 0, bh = b ? label(b)->high : 0;
        return plain_make(LABEL_BITS, op == PNDD_AND ? al & bl : op == PNDD_OR ? al | bl : al & ~bl,
                         op == PNDD_AND ? ah & bh : op == PNDD_OR ? ah | bh : ah & ~bh);
    }
    uint64_t one = truth(field);
    if (mode != PNDD_ZDD) {
        if (op == PNDD_AND && a == one) return b;
        if (op == PNDD_AND && b == one) return a;
        if (op == PNDD_OR && (a == one || b == one)) return one;
        if (op == PNDD_DIFF && b == one) return 0;
        if (mode == PNDD_BCDD && op == PNDD_DIFF && a == one) return b ^ sylvan_complement;
    }
    uint64_t cached;
    if (label_cache_get(mode, op, a, b, &cached)) return cached;
    uint32_t av = label_var(field, a), bv = label_var(field, b), var = av < bv ? av : bv;
    if (var == UINT32_MAX) return op == PNDD_AND ? (a && b ? one : 0) : op == PNDD_OR ? (a || b ? one : 0) : (a && !b ? one : 0);
    uint64_t temporary[4] = {a, b, 0, 0}; RootFrame roots;
    root_push(&roots, field, temporary, sizeof(uint64_t), 4);
    uint64_t al = branch(field, a, var, 0), ah = branch(field, a, var, 1);
    uint64_t bl = branch(field, b, var, 0), bh = branch(field, b, var, 1);
    int spawn = G->config.label_parallel && G->config.workers > 1 && depth < G->config.spawn_depth && (av != UINT32_MAX && bv != UINT32_MAX);
    if (spawn) {
        atomic_fetch_add(&G->tasks, 1);
        Task *task = SPAWN(label_apply, field, op, ah, bh, depth + 1, __lace_worker->worker);
        if (mode == PNDD_BCDD) bdd_refs_spawn(task);
        if (mode == PNDD_ZDD) zdd_refs_spawn(task);
        temporary[2] = CALL(label_apply, field, op, al, bl, depth + 1, __lace_worker->worker);
        uint64_t result = SYNC(label_apply);
        temporary[3] = mode == PNDD_BCDD ? bdd_refs_sync(result) : mode == PNDD_ZDD ? zdd_refs_sync(result) : result;
    } else {
        temporary[2] = CALL(label_apply, field, op, al, bl, depth + 1, __lace_worker->worker);
        temporary[3] = CALL(label_apply, field, op, ah, bh, depth + 1, __lace_worker->worker);
    }
    uint64_t result = make_label(field, var, temporary[2], temporary[3]);
    root_pop(&roots); label_cache_put(mode, op, a, b, result); return result == PNDD_INVALID ? 0 : result;
}
static uint64_t labels(uint32_t field, uint32_t op, uint64_t a, uint64_t b) {
    LACE_VARS;
#ifdef PNDD_PROFILE
    uint64_t start = now_ns();
#endif
    uint64_t result;
#ifdef PNDD_EXPERIMENT_BCDD_AND
    if (G->fields[field].spec.backend == PNDD_BCDD) {
        uint64_t negate = op == PNDD_OR ? sylvan_complement : 0;
        if (op == PNDD_OR) { a ^= sylvan_complement; b ^= sylvan_complement; }
        else if (op == PNDD_DIFF) b ^= sylvan_complement;
        result = CALL(label_apply, field, PNDD_AND, a, b, 0, __lace_worker->worker) ^ negate;
        if (atomic_load(&G->failure)) result = PNDD_INVALID;
    } else
#endif
    result = CALL(label_apply, field, op, a, b, 0, __lace_worker->worker);
#ifdef PNDD_PROFILE
    ++worker_stats()->profile.labels_calls;
    worker_stats()->profile.labels_ns += now_ns() - start;
#endif
    return result;
}
static int compare_edges(const void *a, const void *b) {
    uint32_t x = ((const Edge *)a)->target, y = ((const Edge *)b)->target;
    return x < y ? -1 : x > y;
}
static uint32_t make_node(uint32_t field, Edge *edges, size_t count) {
    if (atomic_load(&G->failure)) return INVALID_ID;
    size_t kept = 0;
    for (size_t i = 0; i < count; ++i) {
        if (edges[i].label == PNDD_INVALID || edges[i].target == INVALID_ID) { fail(1); return INVALID_ID; }
        if (edges[i].label && edges[i].target) edges[kept++] = edges[i];
    }
    if (!kept) return 0;
    RootFrame roots; root_push(&roots, field, edges, sizeof(Edge), kept);
    qsort(edges, kept, sizeof(Edge), compare_edges);
    size_t out = 0;
    for (size_t i = 0; i < kept; ++i) {
        if (out && edges[out - 1].target == edges[i].target)
            edges[out - 1].label = labels(field, PNDD_OR, edges[out - 1].label, edges[i].label);
        else edges[out++] = edges[i];
    }
    if (atomic_load(&G->failure)) { root_pop(&roots); return INVALID_ID; }
    if (out == 1 && edges[0].label == G->fields[field].universe) {
        uint32_t target = edges[0].target; root_pop(&roots); return target;
    }
    uint64_t h = mix(field);
    for (size_t i = 0; i < out; ++i) h = mix(h ^ mix(edges[i].label) ^ edges[i].target);
    Unique *u = &G->node_unique[h % SHARDS]; spin_lock(&u->lock);
    size_t bucket = (h / SHARDS) & (u->size - 1);
    for (uint32_t id = u->buckets[bucket]; id; id = node(id)->next) {
        Node *n = node(id);
        if (n->field != field || n->count != out) continue;
        size_t i = 0; for (; i < out && n->edges[i].target == edges[i].target && n->edges[i].label == edges[i].label; ++i) {}
        if (i == out) { spin_unlock(&u->lock); root_pop(&roots); return id; }
    }
    if (!grow_unique(u, 0)) { spin_unlock(&u->lock); root_pop(&roots); return INVALID_ID; }
    bucket = (h / SHARDS) & (u->size - 1);
    Node *n = allocate(sizeof(Node) + out * sizeof(Edge));
    uint32_t id = INVALID_ID;
    if (n) {
        n->hash = h; n->field = field; n->count = (uint32_t)out; n->next = u->buckets[bucket];
        memcpy(n->edges, edges, out * sizeof(Edge)); id = publish_node(n);
        if (id != INVALID_ID) { u->buckets[bucket] = id; ++u->count; } else deallocate(n);
    }
    spin_unlock(&u->lock); root_pop(&roots); return id;
}
TASK_DECL_4(uint32_t, apply_rec, uint32_t, uint32_t, uint32_t, uint32_t);
TASK_DECL_3(uint32_t, exist_rec, uint32_t, uint32_t, uint32_t);
VOID_TASK_DECL_5(process_jobs, Job *, size_t, uint32_t, uint32_t, uint32_t);
VOID_TASK_IMPL_5(process_jobs, Job *, jobs, size_t, count, uint32_t, op, uint32_t, depth, uint32_t, owner) {
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    if (G->config.ndd_parallel && G->config.workers > 1 && depth < G->config.spawn_depth && count >= G->config.task_threshold && count > G->config.chunk_size) {
        size_t half = count / 2; atomic_fetch_add(&G->tasks, 1);
        SPAWN(process_jobs, jobs, half, op, depth + 1, __lace_worker->worker);
        CALL(process_jobs, jobs + half, count - half, op, depth + 1, __lace_worker->worker);
        SYNC(process_jobs);
    } else for (size_t i = 0; i < count; ++i)
        jobs[i].target = op >= 16 ? CALL(exist_rec, jobs[i].a, op - 16, depth) : CALL(apply_rec, op, jobs[i].a, jobs[i].b, depth);
}
static uint64_t coverage(uint32_t field, const Edge *edges, size_t count) {
    uint64_t value = 0; RootFrame r; root_push(&r, field, &value, sizeof(value), 1);
    for (size_t i = 0; i < count; ++i) value = labels(field, PNDD_OR, value, edges[i].label);
    root_pop(&r); return value;
}
TASK_IMPL_4(uint32_t, apply_rec, uint32_t, op, uint32_t, a, uint32_t, b, uint32_t, depth) {
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || a == INVALID_ID || b == INVALID_ID) return INVALID_ID;
    if ((op == PNDD_AND || op == PNDD_OR) && a > b) { uint32_t t = a; a = b; b = t; }
    if (op == PNDD_AND) { if (!a || !b) return 0; if (a == 1) return b; if (b == 1 || a == b) return a; }
    if (op == PNDD_OR) { if (a == 1 || b == 1) return 1; if (!a) return b; if (!b || a == b) return a; }
    if (op == PNDD_DIFF) { if (!a || b == 1 || a == b) return 0; if (!b) return a; }
    uint64_t result;
    if (pndd_cache_get(op + 1, a, b, &result)) return (uint32_t)result;
    if (op > PNDD_DIFF) {
        uint32_t x, y;
        switch (op) {
        case PNDD_XOR:
            x = CALL(apply_rec, PNDD_DIFF, a, b, depth); y = CALL(apply_rec, PNDD_DIFF, b, a, depth);
            result = CALL(apply_rec, PNDD_OR, x, y, depth); break;
        case PNDD_IMP:
            x = CALL(apply_rec, PNDD_DIFF, 1, a, depth); result = CALL(apply_rec, PNDD_OR, x, b, depth); break;
        case PNDD_EQUIV:
            x = CALL(apply_rec, PNDD_XOR, a, b, depth); result = CALL(apply_rec, PNDD_DIFF, 1, x, depth); break;
        default:
            x = CALL(apply_rec, op == PNDD_NAND ? PNDD_AND : PNDD_OR, a, b, depth);
            result = CALL(apply_rec, PNDD_DIFF, 1, x, depth); break;
        }
        pndd_cache_put(op + 1, a, b, result); return (uint32_t)result;
    }
    uint32_t af = node_field(a), bf = node_field(b), field = af < bf ? af : bf;
#ifdef PNDD_PROFILE
    uint64_t prepare_start = now_ns();
#endif
    Edge av = {G->fields[field].universe, a}, bv = {G->fields[field].universe, b};
    const Edge *ae = af == field ? node(a)->edges : &av, *be = bf == field ? node(b)->edges : &bv;
    size_t ac = af == field ? node(a)->count : 1, bc = bf == field ? node(b)->count : 1;
    if (ac > (SIZE_MAX - ac - bc) / bc) { fail(1); return INVALID_ID; }
    size_t capacity = ac * bc + ac + bc;
    Job *jobs = allocate(capacity * sizeof(Job)); Edge *edges = allocate(capacity * sizeof(Edge));
    if (!jobs || !edges) { deallocate(jobs); deallocate(edges); return INVALID_ID; }
    RootFrame jr, er; root_push(&jr, field, jobs, sizeof(Job), 0); root_push(&er, field, edges, sizeof(Edge), 0);
    uint64_t covered[2] = {0, 0}; RootFrame cr; root_push(&cr, field, covered, sizeof(uint64_t), 2);
    size_t jc = 0, ec = 0;
    for (size_t i = 0; i < ac && !atomic_load(&G->failure); ++i) for (size_t j = 0; j < bc; ++j) {
        uint64_t intersection = labels(field, PNDD_AND, ae[i].label, be[j].label);
        if (intersection && intersection != PNDD_INVALID) {
            jobs[jc] = (Job){intersection, ae[i].target, be[j].target, 0}; jr.count = ++jc;
        }
    }
    if (op != PNDD_AND && !atomic_load(&G->failure)) {
        covered[1] = coverage(field, be, bc);
        for (size_t i = 0; i < ac; ++i) {
            uint64_t residual = labels(field, PNDD_DIFF, ae[i].label, covered[1]);
            if (residual && residual != PNDD_INVALID) { edges[ec++] = (Edge){residual, ae[i].target}; er.count = ec; }
        }
        if (op == PNDD_OR) {
            covered[0] = coverage(field, ae, ac);
            for (size_t i = 0; i < bc; ++i) {
                uint64_t residual = labels(field, PNDD_DIFF, be[i].label, covered[0]);
                if (residual && residual != PNDD_INVALID) { edges[ec++] = (Edge){residual, be[i].target}; er.count = ec; }
            }
        }
    }
#ifdef PNDD_PROFILE
    Profile *p = &worker_stats()->profile;
    ++p->misses; p->prepare_ns += now_ns() - prepare_start; p->jobs += jc;
    ++p->bins[jc == 0 ? 0 : jc == 1 ? 1 : jc < 4 ? 2 : jc < 16 ? 3 : 4];
    for (size_t i = 0; i < jc; ++i)
        if (jobs[i].a > 1 && jobs[i].b > 1 && jobs[i].a != jobs[i].b) ++p->costly_jobs;
#endif
    CALL(process_jobs, jobs, jc, op, depth, __lace_worker->worker);
    uint64_t start = now_ns();
    for (size_t i = 0; i < jc; ++i) { edges[ec++] = (Edge){jobs[i].label, jobs[i].target}; er.count = ec; }
    result = make_node(field, edges, ec);
    worker_stats()->merge_ns += now_ns() - start;
    root_pop(&cr); root_pop(&er); root_pop(&jr); deallocate(edges); deallocate(jobs);
    pndd_cache_put(op + 1, a, b, result); return (uint32_t)result;
}
TASK_DECL_5(uint32_t, reduce_rec, uint32_t, const uint32_t *, size_t, uint32_t, uint32_t);
TASK_IMPL_5(uint32_t, reduce_rec, uint32_t, op, const uint32_t *, values, size_t, count, uint32_t, depth, uint32_t, owner) {
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    if (!count) return op == PNDD_AND ? 1 : 0;
    if (count == 1) return values[0];
    size_t half = count / 2; uint32_t left, right;
    if (G->config.batch_parallel && G->config.workers > 1 && depth < G->config.spawn_depth && count > 2) {
        atomic_fetch_add(&G->tasks, 1);
        SPAWN(reduce_rec, op, values, half, depth + 1, __lace_worker->worker);
        right = CALL(reduce_rec, op, values + half, count - half, depth + 1, __lace_worker->worker);
        left = SYNC(reduce_rec);
    } else {
        left = CALL(reduce_rec, op, values, half, depth + 1, __lace_worker->worker);
        right = CALL(reduce_rec, op, values + half, count - half, depth + 1, __lace_worker->worker);
    }
    return CALL(apply_rec, op, left, right, 0);
}
TASK_IMPL_3(uint32_t, exist_rec, uint32_t, a, uint32_t, field, uint32_t, depth) {
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || a == INVALID_ID) return INVALID_ID;
    if (node_field(a) > field) return a;
    uint64_t cached; if (pndd_cache_get(0x10000 + field, a, 0, &cached)) return (uint32_t)cached;
    Node *n = node(a); uint32_t result;
    if (n->field == field) {
        uint32_t *targets = allocate(n->count * sizeof(uint32_t)); if (!targets) return INVALID_ID;
        for (size_t i = 0; i < n->count; ++i) targets[i] = n->edges[i].target;
        result = CALL(reduce_rec, PNDD_OR, targets, n->count, 0, __lace_worker->worker); deallocate(targets);
    } else {
        Job *jobs = allocate(n->count * sizeof(Job)); Edge *edges = allocate(n->count * sizeof(Edge));
        if (!jobs || !edges) { deallocate(jobs); deallocate(edges); return INVALID_ID; }
        for (size_t i = 0; i < n->count; ++i) jobs[i] = (Job){n->edges[i].label, n->edges[i].target, 0, 0};
        CALL(process_jobs, jobs, n->count, 16 + field, depth, __lace_worker->worker);
        for (size_t i = 0; i < n->count; ++i) edges[i] = (Edge){jobs[i].label, jobs[i].target};
        result = make_node(n->field, edges, n->count); deallocate(jobs); deallocate(edges);
    }
    pndd_cache_put(0x10000 + field, a, 0, result); return result;
}
/* Sylvan may collect during any label allocation. The frame pauses all workers;
 * marking every allocated NDD preserves operation-cache and unpublished-child
 * results. Temporary label vectors and label-task results are separate roots. */
VOID_TASK_DECL_0(mark_labels);
VOID_TASK_IMPL_0(mark_labels) {
    for (size_t f = 0; f < G->field_count; ++f) {
        if (G->fields[f].spec.backend == PNDD_BCDD) CALL(mtbdd_gc_mark_rec, G->fields[f].universe);
        if (G->fields[f].spec.backend == PNDD_ZDD) CALL(zdd_gc_mark_rec, G->fields[f].universe);
    }
    for (uint32_t id = 2; id < G->node_high; ++id) {
        Node *n = node(id); if (!n) continue;
        pndd_backend mode = G->fields[n->field].spec.backend;
        for (size_t i = 0; i < n->count; ++i) {
            if (mode == PNDD_BCDD) CALL(mtbdd_gc_mark_rec, n->edges[i].label);
            if (mode == PNDD_ZDD) CALL(zdd_gc_mark_rec, n->edges[i].label);
        }
    }
    for (unsigned w = 0; w < G->config.workers; ++w) for (RootFrame *r = G->roots[w]; r; r = r->previous) {
        pndd_backend mode = G->fields[r->field].spec.backend;
        if (mode != PNDD_BCDD && mode != PNDD_ZDD) continue;
        for (size_t i = 0; i < r->count; ++i) {
            uint64_t value = *(uint64_t *)((char *)r->values + i * r->stride);
            if (value == PNDD_INVALID) continue;
            if (mode == PNDD_BCDD) CALL(mtbdd_gc_mark_rec, value); else CALL(zdd_gc_mark_rec, value);
        }
    }
}
static void sylvan_pre(WorkerP *worker, Task *task) {
    (void)worker; (void)task;
    memset(G->cache, 0, G->config.cache_entries * sizeof(Cache));
    atomic_fetch_add(&G->label_collections, 1);
}
static void mark_node(uint32_t id) {
    if (id < 2) return;
    Node *n = node(id); if (!n || n->mark) return; n->mark = 1;
    for (size_t i = 0; i < n->count; ++i) mark_node(n->edges[i].target);
}
static void mark_plain(uint64_t id, int bits) {
    if (id == 0 || (id == 1 && !bits)) return;
    Label *l = label(id); if (!l || l->mark) return; l->mark = 1;
    if (!bits) { mark_plain(l->low, 0); mark_plain(l->high, 0); }
}
VOID_TASK_DECL_0(sweep_nodes);
VOID_TASK_IMPL_0(sweep_nodes) {
    /* Only invoked at an admitted operation boundary. No active NDD allocation. */
    for (unsigned i = 0; i < G->config.workers; ++i) {
        WorkerStats *w = &G->worker_stats[i];
        atomic_fetch_add(&G->nodes_live, w->nodes_live); w->nodes_live = 0;
        atomic_fetch_add(&G->labels_live, w->labels_live); w->labels_live = 0;
        w->node_next = w->node_end = 0;
    }
    memset(G->cache, 0, G->config.cache_entries * sizeof(Cache));
    for (uint32_t i = 2; i < G->node_high; ++i) { Node *n = node(i); if (n) n->mark = 0; }
    for (uint32_t i = 2; i < G->label_high; ++i) { Label *l = label(i); if (l) l->mark = 0; }
    for (uint32_t i = 2; i < G->node_high; ++i) { Node *n = node(i); if (n && n->refs) mark_node(i); }
    for (size_t f = 0; f < G->field_count; ++f) {
        Field *field = &G->fields[f];
        for (size_t i = 0; i < field->spec.width * 2; ++i) mark_node(field->literals[i]);
        if (field->spec.backend == PNDD_BITSET) mark_plain(field->universe, 1);
    }
    for (size_t s = 0; s < SHARDS; ++s) {
        memset(G->node_unique[s].buckets, 0, G->node_unique[s].size * sizeof(uint32_t)); G->node_unique[s].count = 0;
        memset(G->label_unique[s].buckets, 0, G->label_unique[s].size * sizeof(uint32_t)); G->label_unique[s].count = 0;
    }
    G->free_node_count = G->free_label_count = 0;
    for (uint32_t i = 2; i < G->node_high; ++i) {
        Node *n = node(i);
        if (n && !n->mark) { deallocate(n); atomic_store((_Atomic(Node *) *)&atomic_load(&G->node_blocks[i / BLOCK])[i % BLOCK], NULL); atomic_fetch_sub(&G->nodes_live, 1); n = NULL; }
        if (!n) { G->free_nodes[G->free_node_count++] = i; continue; }
        Unique *u = &G->node_unique[n->hash % SHARDS]; size_t bucket = (n->hash / SHARDS) & (u->size - 1);
        n->next = u->buckets[bucket]; u->buckets[bucket] = i; ++u->count;
        pndd_backend mode = G->fields[n->field].spec.backend;
        if (mode == PNDD_BDD || mode == PNDD_BITSET) for (size_t j = 0; j < n->count; ++j) mark_plain(n->edges[j].label, mode == PNDD_BITSET);
    }
    for (uint32_t i = 2; i < G->label_high; ++i) {
        Label **block = atomic_load(&G->label_blocks[i / BLOCK]); Label *l = block[i % BLOCK];
        if (l && !l->mark) { deallocate(l); block[i % BLOCK] = NULL; atomic_fetch_sub(&G->labels_live, 1); l = NULL; }
        if (!l) { G->free_labels[G->free_label_count++] = i; continue; }
        Unique *u = &G->label_unique[l->hash % SHARDS]; size_t bucket = (l->hash / SHARDS) & (u->size - 1);
        l->next = u->buckets[bucket]; u->buckets[bucket] = i; ++u->count;
    }
    atomic_fetch_add(&G->collections, 1); G->last_gc_bytes = atomic_load(&G->bytes);
}
VOID_TASK_DECL_0(collect_task);
VOID_TASK_IMPL_0(collect_task) {
    /* Freelist capacity is sized outside the stopped frame and reused. */
    NEWFRAME(sweep_nodes);
    if (G->sylvan) CALL(sylvan_gc);
}
static int prepare_collect(void) {
    /* These are auxiliary memory, included in the memory budget. */
    G->allocation_limit = G->hard_limit;
    uint32_t *nf = allocate((size_t)G->node_high * sizeof(uint32_t));
    uint32_t *lf = allocate((size_t)G->label_high * sizeof(uint32_t));
    G->allocation_limit = G->hard_limit * 4 / 5;
    if (!nf || !lf) { deallocate(nf); deallocate(lf); return 0; }
    deallocate(G->free_nodes); deallocate(G->free_labels);
    G->free_nodes = nf; G->free_labels = lf; return 1;
}
static int collect_internal(void) {
    PROFILE_START;
    if (!prepare_collect()) return 0;
    atomic_fetch_add(&G->runs, 1); RUN(collect_task);
    PROFILE_END(4); return !atomic_load(&G->failure);
}
static int begin_deferred(pndd_engine *engine, int defer_gc) {
    if (!engine || engine != G) { open_error = "Closed or foreign engine"; return 0; }
    G->pending_gc = 0;
    atomic_store(&G->failure, 0);
    ++G->admissions;
    if ((G->config.gc_interval && G->admissions % G->config.gc_interval == 0) ||
        (atomic_load(&G->bytes) > G->allocation_limit * 3 / 4 && atomic_load(&G->bytes) > G->last_gc_bytes + 65536)) {
        if (defer_gc) G->pending_gc = 1;
        else if (!collect_internal()) return 0;
    }
    return 1;
}
static int begin(pndd_engine *engine) { return begin_deferred(engine, 0); }
/* Called from the admitted top-level Lace task before creating temporary IDs. */
static int collect_deferred(void) {
    if (!G->pending_gc) return 1;
    G->pending_gc = 0;
    if (!prepare_collect()) return 0;
    LACE_VARS; CALL(collect_task);
    return !atomic_load(&G->failure);
}
static uint32_t resolve(uint64_t handle) {
    uint32_t id = (uint32_t)handle;
    if (handle == PNDD_INVALID || id >= G->node_high) { fail(2); return INVALID_ID; }
    Node *n = node(id);
    if (!n || n->cookie != (uint32_t)(handle >> 32) || !n->refs) { fail(2); return INVALID_ID; }
    return id;
}
static pndd_handle own(uint32_t id) {
    if (id == INVALID_ID || atomic_load(&G->failure)) return PNDD_INVALID;
    Node *n = node(id); if (n->refs == UINT32_MAX) { fail(2); return PNDD_INVALID; }
    ++n->refs; return ((uint64_t)n->cookie << 32) | id;
}
static double gap(uint32_t first, uint32_t last) {
    int bits = 0; for (uint32_t i = first; i < last; ++i) bits += (int)G->fields[i].spec.width;
    return ldexp(1.0, bits);
}
static double label_count(uint32_t field, uint64_t value, uint32_t start) {
    pndd_backend mode = G->fields[field].spec.backend;
    if (!value) return 0;
    if (mode == PNDD_BITSET) return ldexp((double)(__builtin_popcountll(label(value)->low) + __builtin_popcountll(label(value)->high)), -(int)(G->widths[mode] - G->fields[field].spec.width));
    uint64_t bits; uint32_t key = 0x200 + mode * 8192 + start;
    if (pndd_cache_get(key, value, label_end(field), &bits)) { double d; memcpy(&d, &bits, 8); return d; }
    double count;
    if (value == truth(field)) count = mode == PNDD_ZDD ? 1 : ldexp(1.0, (int)(label_end(field) - start));
    else {
        uint32_t var = label_var(field, value);
        count = label_count(field, branch(field, value, var, 0), var + 1) + label_count(field, branch(field, value, var, 1), var + 1);
        if (mode != PNDD_ZDD) count = ldexp(count, (int)(var - start));
    }
    memcpy(&bits, &count, 8); pndd_cache_put(key, value, label_end(field), bits); return count;
}
static double count_node(uint32_t id, double *memo, unsigned char *seen) {
    if (id < 2) return (double)id;
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    if (node(id)->sat_count_valid) return node(id)->sat_count;
#else
    if (seen[id]) return memo[id];
#endif
    Node *n = node(id); double result = 0;
    for (size_t i = 0; i < n->count; ++i) {
        uint32_t child = n->edges[i].target;
        double lc = label_count(n->field, n->edges[i].label, G->fields[n->field].start);
        result += lc * gap(n->field + 1, node_field(child)) * count_node(child, memo, seen);
    }
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    n->sat_count = result; n->sat_count_valid = 1;
#else
    seen[id] = 1; memo[id] = result;
#endif
    return result;
}
static double satcount_internal(uint32_t id) {
    if (id == INVALID_ID) return NAN;
    if (id == 0) return 0;
    if (id == 1) return gap(0, (uint32_t)G->field_count);
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    return gap(0, node_field(id)) * count_node(id, NULL, NULL);
#else
    double *memo = allocate((size_t)G->node_high * sizeof(double)); unsigned char *seen = allocate(G->node_high);
    if (!memo || !seen) { deallocate(memo); deallocate(seen); return NAN; }
    double result = gap(0, node_field(id)) * count_node(id, memo, seen);
    deallocate(memo); deallocate(seen); return result;
#endif
}
static int label_matches(uint32_t field, uint64_t value, uint64_t assignment) {
    pndd_backend mode = G->fields[field].spec.backend; Field *f = &G->fields[field];
    if (mode == PNDD_BITSET) {
        if (!value) return 0;
        Label *l = label(value);
        return assignment < 64 ? (int)((l->low >> assignment) & 1) : (int)((l->high >> (assignment - 64)) & 1);
    }
    if (mode == PNDD_ZDD) {
        for (uint32_t var = f->start; var < G->widths[mode]; ++var) {
            uint32_t shift = G->widths[mode] - 1 - var;
            int bit = shift < 64 ? (int)((assignment >> shift) & 1) : 0;
            value = branch(field, value, var, bit); if (!value) return 0;
        }
    } else while (value && value != truth(field)) {
        uint32_t var = label_var(field, value);
        uint32_t shift = reversed(field) ? var : label_end(field) - 1 - var;
        value = branch(field, value, var, shift < 64 ? (int)((assignment >> shift) & 1) : 0);
    }
    return value == truth(field);
}
TASK_DECL_4(uint32_t, construct, uint32_t, uint32_t, uint64_t, int);
TASK_IMPL_4(uint32_t, construct, uint32_t, field, uint32_t, bit, uint64_t, value, int, type) {
    Field *f = &G->fields[field]; pndd_backend mode = f->spec.backend;
    if (type == 0) return f->literals[bit * 2 + !!value];
    uint64_t current = truth(field); RootFrame root; root_push(&root, field, &current, sizeof(current), 1);
    if (mode == PNDD_BITSET) {
        uint64_t low = 0, high = 0;
        for (unsigned assignment = 0; assignment < (1u << G->widths[mode]); ++assignment)
            if ((assignment & ((UINT64_C(1) << f->spec.width) - 1)) == value) {
                if (assignment < 64) low |= UINT64_C(1) << assignment; else high |= UINT64_C(1) << (assignment - 64);
            }
        current = plain_make(LABEL_BITS, low, high);
    } else for (uint32_t v = label_end(field); v > f->start; --v) {
        uint32_t shift = reversed(field) ? v - 1 : label_end(field) - v;
        int positive = shift < 64 ? (int)((value >> shift) & 1) : 0;
        current = make_label(field, v - 1, positive ? 0 : current, positive ? current : 0);
        if (current == PNDD_INVALID) break;
    }
    Edge edge = {current, 1}; uint32_t result = make_node(field, &edge, 1); root_pop(&root); return result;
}
VOID_TASK_DECL_0(initialize_fields);
VOID_TASK_IMPL_0(initialize_fields) {
    for (uint32_t f = 0; f < G->field_count && !atomic_load(&G->failure); ++f) {
        Field *field = &G->fields[f]; pndd_backend mode = field->spec.backend;
        field->universe = truth(f);
        if (mode == PNDD_BITSET) {
            unsigned count = 1u << G->widths[mode];
            field->universe = plain_make(LABEL_BITS, count >= 64 ? UINT64_MAX : (UINT64_C(1) << count) - 1, count == 128 ? UINT64_MAX : 0);
        } else if (mode == PNDD_ZDD) {
            for (uint32_t v = G->widths[mode]; v > field->start; --v)
                field->universe = make_label(f, v - 1, field->universe, field->universe);
        }
        for (uint32_t bit = 0; bit < field->spec.width && !atomic_load(&G->failure); ++bit) for (unsigned positive = 0; positive < 2; ++positive) {
            uint64_t value = truth(f); RootFrame root; root_push(&root, f, &value, sizeof(value), 1);
            uint32_t variable = reversed(f) ? field->spec.width - 1 - bit : field->start + bit;
            if (mode == PNDD_BITSET) {
                uint64_t low = 0, high = 0;
                for (unsigned a = 0; a < (1u << G->widths[mode]); ++a)
                    if (((a >> (G->widths[mode] - 1 - variable)) & 1) == positive) {
                        if (a < 64) low |= UINT64_C(1) << a; else high |= UINT64_C(1) << (a - 64);
                    }
                value = plain_make(LABEL_BITS, low, high);
            } else if (mode == PNDD_ZDD) {
                for (uint32_t v = G->widths[mode]; v > field->start && value != PNDD_INVALID; --v)
                    value = make_label(f, v - 1, v - 1 == variable && positive ? 0 : value, v - 1 == variable && !positive ? 0 : value);
            } else value = make_label(f, variable, positive ? 0 : value, positive ? value : 0);
            Edge edge = {value, 1}; field->literals[bit * 2 + positive] = make_node(f, &edge, 1); root_pop(&root);
        }
    }
}
pndd_config pndd_default_config(void) {
    return (pndd_config){1, 4, 2, 4, 1, 0, 1, 512u * 1024 * 1024, 262144, 128, 0};
}
static void destroy_engine(void) {
#ifdef PNDD_PROFILE
    profile_dump();
#endif
    if (G->sylvan) sylvan_quit();
    lace_stop();
    for (uint32_t i = 0; i < G->node_high; ++i) deallocate(node(i));
    for (uint32_t i = 2; i < G->label_high; ++i) deallocate(label(i));
    for (size_t i = 0; i < G->field_count; ++i) deallocate(G->fields[i].literals);
    for (size_t i = 0; i < BLOCKS; ++i) { deallocate(atomic_load(&G->node_blocks[i])); deallocate(atomic_load(&G->label_blocks[i])); }
    for (size_t i = 0; i < SHARDS; ++i) {
        deallocate(G->node_unique[i].buckets); deallocate(G->label_unique[i].buckets);

    }
    deallocate(G->cache); deallocate(G->roots); deallocate(G->free_nodes); deallocate(G->free_labels);
    pthread_mutex_destroy(&G->allocation); free(G); G = NULL;
}
pndd_engine *pndd_open(const pndd_config *requested, const pndd_field *fields, size_t count) {
    pthread_mutex_lock(&admission);
    pndd_config c = requested ? *requested : pndd_default_config();
    if (G) { open_error = "Only one active native engine is supported"; pthread_mutex_unlock(&admission); return NULL; }
    if (!fields || !count || count > MAX_FIELDS || !c.workers || c.workers > 256 || c.memory_bytes < 4u * 1024 * 1024 ||
        !c.cache_entries || c.cache_entries > SIZE_MAX / sizeof(Cache) || (c.cache_entries & (c.cache_entries - 1)) || !c.task_threshold || !c.chunk_size || c.spawn_depth > 32) {
        open_error = "Invalid engine configuration"; pthread_mutex_unlock(&admission); return NULL;
    }
    for (size_t i = 0; i < count; ++i) if (!fields[i].width || fields[i].width > 4096 || fields[i].backend > PNDD_BITSET || fields[i].backend < PNDD_BDD || (fields[i].backend == PNDD_BITSET && fields[i].width > 7)) {
        open_error = "Field widths must be 1..4096 (BITSET: 1..7)"; pthread_mutex_unlock(&admission); return NULL;
    }
    /* The shard locks make the engine over-aligned; calloc only guarantees
       fundamental alignment. sizeof(pndd_engine) is a multiple of its alignment. */
    G = aligned_alloc(_Alignof(pndd_engine), sizeof(*G));
    if (G) memset(G, 0, sizeof(*G));
    if (!G) { open_error = "Native memory budget exhausted"; pthread_mutex_unlock(&admission); return NULL; }
    G->config = c; G->field_count = count; G->label_high = 2;
    for (size_t i = 0; i < count; ++i) {
        G->fields[i].spec = fields[i];
        if (G->widths[fields[i].backend] < fields[i].width) G->widths[fields[i].backend] = fields[i].width;
        if (fields[i].backend == PNDD_BCDD || fields[i].backend == PNDD_ZDD) G->sylvan = 1;
    }
    for (size_t i = 0; i < count; ++i) G->fields[i].start = reversed((uint32_t)i) ? 0 : G->widths[fields[i].backend] - fields[i].width;
    G->hard_limit = c.memory_bytes - (G->sylvan ? c.memory_bytes / 4 : 0);
    G->allocation_limit = G->hard_limit * 4 / 5;
    /* Include the stable block directory and engine metadata in the budget. */
    atomic_store(&G->bytes, sizeof(*G)); atomic_store(&G->peak, sizeof(*G));
    pthread_mutex_init(&G->allocation, NULL);
    for (size_t i = 0; i < count; ++i)
        G->fields[i].literals = allocate(fields[i].width * 2 * sizeof(uint32_t));
    for (size_t i = 0; i < SHARDS; ++i) {
        atomic_flag_clear(&G->node_unique[i].lock.flag); atomic_flag_clear(&G->label_unique[i].lock.flag); atomic_flag_clear(&G->cache_locks[i].flag);
        G->node_unique[i].size = G->label_unique[i].size = 16;
        G->node_unique[i].buckets = allocate(16 * sizeof(uint32_t)); G->label_unique[i].buckets = allocate(16 * sizeof(uint32_t));
    }
    G->cache = allocate(c.cache_entries * sizeof(Cache)); G->roots = allocate(c.workers * sizeof(RootFrame *));
    lace_start(c.workers, 100000);
    if (G->sylvan) {
        sylvan_set_limits(c.memory_bytes / 4, 2, 4); sylvan_init_package(); sylvan_init_bdd(); sylvan_init_zdd();
#ifdef PNDD_EXPERIMENT_SYLVAN_CACHE
        G->label_cache_opid = cache_next_opid();
#endif
        sylvan_gc_add_mark(mark_labels_CALL); sylvan_gc_hook_pregc(sylvan_pre);
    }
    for (unsigned i = 0; i < 2 && !atomic_load(&G->failure); ++i) {
        Node *n = allocate(sizeof(Node)); if (n) { n->field = (uint32_t)count; n->refs = 1; if (publish_node(n) == INVALID_ID) deallocate(n); }
    }
    if (!atomic_load(&G->failure)) RUN(initialize_fields);
    if (atomic_load(&G->failure)) { open_error = "Native memory budget exhausted during initialization"; destroy_engine(); pthread_mutex_unlock(&admission); return NULL; }
    G->last_gc_bytes = atomic_load(&G->bytes);
    pndd_engine *result = G; pthread_mutex_unlock(&admission); return result;
}
void pndd_close(pndd_engine *e) { pthread_mutex_lock(&admission); if (e && e == G) destroy_engine(); pthread_mutex_unlock(&admission); }
const char *pndd_error(pndd_engine *e) {
    if (!e || e != G) return open_error;
    switch (atomic_load(&G->failure)) { case 1: return "Native memory budget or label table exhausted"; case 2: return "Released, stale or foreign node handle"; case 3: return "Invalid operation, field, bit or assignment"; default: return "Success"; }
}
pndd_handle pndd_constant(pndd_engine *e, int value) {
    pthread_mutex_lock(&admission); pndd_handle h = begin(e) ? own(!!value) : PNDD_INVALID; pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_literal(pndd_engine *e, uint32_t field, uint32_t bit, int positive) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) { if (field >= G->field_count || bit >= G->fields[field].spec.width) fail(3); else h = own(G->fields[field].literals[bit * 2 + !!positive]); }
    pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_value(pndd_engine *e, uint32_t field, uint64_t value) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) {
        if (field >= G->field_count || (G->fields[field].spec.width < 64 && value >= (UINT64_C(1) << G->fields[field].spec.width))) fail(3);
        else { atomic_fetch_add(&G->runs, 1); h = own(RUN(construct, field, 0, value, 1)); }
    }
    pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_apply(pndd_engine *e, pndd_op op, pndd_handle a, pndd_handle b) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    PROFILE_START;
    if (begin(e)) {
        uint32_t x = resolve(a), y = resolve(b);
        if (op > PNDD_NOR || op < PNDD_AND) fail(3);
        if (!atomic_load(&G->failure)) {
            if ((op == PNDD_AND || op == PNDD_OR) && x > y) { uint32_t t = x; x = y; y = t; }
            uint64_t cached;
            if (pndd_cache_get(op + 1, x, y, &cached)) h = own((uint32_t)cached);
            else { atomic_fetch_add(&G->runs, 1); h = own(RUN(apply_rec, op, x, y, 0)); }
        }
    }
    PROFILE_END(0); pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_not(pndd_engine *e, pndd_handle a) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) { uint32_t x = resolve(a); if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); h = own(RUN(apply_rec, PNDD_DIFF, 1, x, 0)); } }
    pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_exist(pndd_engine *e, pndd_handle a, uint32_t field) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    PROFILE_START;
    if (begin(e)) {
        uint32_t x = resolve(a); if (field >= G->field_count) fail(3);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); h = own(RUN(exist_rec, x, field, 0)); }
    }
    PROFILE_END(6); pthread_mutex_unlock(&admission); return h;
}
pndd_handle pndd_retain(pndd_engine *e, pndd_handle a) {
    pthread_mutex_lock(&admission); pndd_handle h = begin(e) ? own(resolve(a)) : PNDD_INVALID; pthread_mutex_unlock(&admission); return h;
}
int pndd_release(pndd_engine *e, pndd_handle a) {
    pthread_mutex_lock(&admission); int result = 0;
    if (e && e == G) { atomic_store(&G->failure, 0); uint32_t id = resolve(a); if (id != INVALID_ID) { --node(id)->refs; result = 1; } }
    pthread_mutex_unlock(&admission); return result;
}
double pndd_satcount(pndd_engine *e, pndd_handle a) {
    pthread_mutex_lock(&admission); double result = NAN;
    PROFILE_START;
    if (begin(e)) result = satcount_internal(resolve(a));
    PROFILE_END(1); pthread_mutex_unlock(&admission); return result;
}
int pndd_evaluate(pndd_engine *e, pndd_handle a, const uint64_t *values, size_t count) {
    pthread_mutex_lock(&admission); int result = -1;
    if (begin(e)) {
        uint32_t id = resolve(a);
        if (!values || count != G->field_count) fail(3);
        if (!atomic_load(&G->failure)) for (size_t i = 0; i < count; ++i)
            if (G->fields[i].spec.width < 64 && values[i] >= (UINT64_C(1) << G->fields[i].spec.width)) fail(3);
        if (!atomic_load(&G->failure)) {
            while (id >= 2) {
                Node *n = node(id); id = 0;
                for (size_t i = 0; i < n->count; ++i) if (label_matches(n->field, n->edges[i].label, values[n->field])) { id = n->edges[i].target; break; }
            }
            result = (int)id;
        }
    }
    pthread_mutex_unlock(&admission); return result;
}
VOID_TASK_DECL_6(batch_rec, uint32_t, const uint32_t *, const uint32_t *, uint32_t *, size_t, uint32_t);
VOID_TASK_IMPL_6(batch_rec, uint32_t, op, const uint32_t *, a, const uint32_t *, b, uint32_t *, out, size_t, count, uint32_t, depth) {
    if (G->config.batch_parallel && G->config.workers > 1 && count > G->config.chunk_size && depth < G->config.spawn_depth) {
        size_t half = count / 2; atomic_fetch_add(&G->tasks, 1);
        SPAWN(batch_rec, op, a, b, out, half, depth + 1);
        CALL(batch_rec, op, a + half, b + half, out + half, count - half, depth + 1);
        SYNC(batch_rec);
    } else for (size_t i = 0; i < count; ++i) out[i] = CALL(apply_rec, op, a[i], b[i], 0);
}
VOID_TASK_DECL_5(batch_top, uint32_t, const pndd_handle *, const pndd_handle *, size_t, pndd_handle *);
VOID_TASK_IMPL_5(batch_top, uint32_t, op, const pndd_handle *, a, const pndd_handle *, b, size_t, count, pndd_handle *, out) {
    if (!collect_deferred()) return;
    uint32_t *ids = allocate(count * 3 * sizeof(uint32_t));
    if (!ids) return;
    for (size_t i = 0; i < count; ++i) { ids[i] = resolve(a[i]); ids[count + i] = resolve(b[i]); }
    if (!atomic_load(&G->failure)) CALL(batch_rec, op, ids, ids + count, ids + 2 * count, count, 0);
    size_t owned = 0;
    if (!atomic_load(&G->failure)) for (; owned < count; ++owned) {
        out[owned] = own(ids[2 * count + owned]);
        if (out[owned] == PNDD_INVALID) break;
    }
    if (atomic_load(&G->failure)) for (size_t i = 0; i < owned; ++i) {
        --node(ids[2 * count + i])->refs; out[i] = PNDD_INVALID;
    }
    deallocate(ids);
}
int pndd_apply_batch(pndd_engine *e, pndd_op op, const pndd_handle *a, const pndd_handle *b, size_t count, pndd_handle *out) {
    pthread_mutex_lock(&admission); int success = 0;
    if (begin_deferred(e, 1)) {
        if (op < PNDD_AND || op > PNDD_NOR || (count && (!a || !b || !out)) || count > SIZE_MAX / (3 * sizeof(uint32_t))) fail(3);
        if (!atomic_load(&G->failure)) {
            atomic_fetch_add(&G->runs, 1); RUN(batch_top, op, a, b, count, out);
            success = !atomic_load(&G->failure);
        }
    }
    pthread_mutex_unlock(&admission); return success;
}
TASK_DECL_4(uint32_t, reduction, uint32_t, const uint32_t *, size_t, int);
TASK_IMPL_4(uint32_t, reduction, uint32_t, op, const uint32_t *, values, size_t, count, int, tree) {
    if (tree) return CALL(reduce_rec, op, values, count, 0, __lace_worker->worker);
    uint32_t result = op == PNDD_AND ? 1 : 0;
    for (size_t i = 0; i < count; ++i) result = CALL(apply_rec, op, result, values[i], 0);
    return result;
}
TASK_DECL_4(uint32_t, reduce_top, uint32_t, const pndd_handle *, size_t, int);
TASK_IMPL_4(uint32_t, reduce_top, uint32_t, op, const pndd_handle *, values, size_t, count, int, tree) {
    if (!collect_deferred()) return INVALID_ID;
    uint32_t *ids = allocate(count * sizeof(uint32_t));
    if (!ids) return INVALID_ID;
    for (size_t i = 0; i < count; ++i) ids[i] = resolve(values[i]);
    uint32_t result = atomic_load(&G->failure) ? INVALID_ID : CALL(reduction, op, ids, count, tree);
    deallocate(ids); return result;
}
pndd_handle pndd_reduce(pndd_engine *e, pndd_op op, const pndd_handle *values, size_t count, int tree) {
    pthread_mutex_lock(&admission); pndd_handle result = PNDD_INVALID;
    if (begin_deferred(e, 1)) {
        if ((op != PNDD_AND && op != PNDD_OR) || (count && !values) || count > SIZE_MAX / sizeof(uint32_t)) fail(3);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); result = own(RUN(reduce_top, op, values, count, tree)); }
    }
    pthread_mutex_unlock(&admission); return result;
}
int pndd_collect(pndd_engine *e) {
    pthread_mutex_lock(&admission); int result = 0;
    if (e && e == G) { atomic_store(&G->failure, 0); result = collect_internal(); }
    pthread_mutex_unlock(&admission); return result;
}
pndd_stats pndd_get_stats(pndd_engine *e) {
    pthread_mutex_lock(&admission); pndd_stats s = {0};
    if (e && e == G) s = (pndd_stats){atomic_load(&G->nodes_live), atomic_load(&G->nodes_created), atomic_load(&G->labels_live), atomic_load(&G->labels_created), atomic_load(&G->tasks), atomic_load(&G->stolen), atomic_load(&G->hits), atomic_load(&G->misses), atomic_load(&G->collections), atomic_load(&G->runs), atomic_load(&G->merge_ns), atomic_load(&G->bytes), atomic_load(&G->peak), atomic_load(&G->label_collections), G->build_ns, G->reduce_ns, G->count_ns};
    if (e && e == G) for (unsigned i = 0; i < G->config.workers; ++i) {
        WorkerStats *w = &G->worker_stats[i];
        s.merge_ns += w->merge_ns;
        s.cache_hits += w->hits; s.cache_misses += w->misses;
        s.nodes_live += w->nodes_live; s.nodes_created += w->nodes_created;
        s.labels_live += w->labels_live; s.labels_created += w->labels_created;
    }
    if (e && e == G && G->sylvan) {
        size_t filled = 0, total = 0; sylvan_table_usage(&filled, &total);
        s.labels_live += filled;
    }
    pthread_mutex_unlock(&admission); return s;
}
/* Benchmark construction matches NDDSolution.java: row ORs, four independent
 * implication folds per cell, then c&d, b&c, a&b and fixed row/cell left-fold. */
VOID_TASK_DECL_2(snapshot_task, uint32_t, pndd_handle *);
VOID_TASK_IMPL_2(snapshot_task, uint32_t, id, pndd_handle *, out) {
    Node *n = node(id);
    for (size_t i = 0; i < n->count && !atomic_load(&G->failure); ++i) {
        out[i * 2] = own(n->edges[i].target);
        Edge edge = {n->edges[i].label, 1};
        out[i * 2 + 1] = own(make_node(n->field, &edge, 1));
    }
}
int pndd_snapshot(pndd_engine *e, pndd_handle handle, uint32_t *field,
                  size_t *count, pndd_handle **pairs) {
    pthread_mutex_lock(&admission); int ok = 0; *count = 0; *pairs = NULL;
    PROFILE_START;
    if (begin(e)) {
        uint32_t id = resolve(handle);
        if (id != INVALID_ID) {
            Node *n = node(id); *field = n->field; size_t size = n->count;
            pndd_handle *out = malloc(size ? size * 2 * sizeof(*out) : 1);
            if (!out) fail(1);
            else {
                for (size_t i = 0; i < size * 2; ++i) out[i] = PNDD_INVALID;
                if (size) { atomic_fetch_add(&G->runs, 1); RUN(snapshot_task, id, out); }
                if (atomic_load(&G->failure)) {
                    for (size_t i = 0; i < size * 2; ++i)
                        if (out[i] != PNDD_INVALID) --node((uint32_t)out[i])->refs;
                    free(out);
                } else { *count = size; *pairs = out; ok = 1; }
            }
        }
    }
    PROFILE_END(2); pthread_mutex_unlock(&admission); return ok;
}
int pndd_reachable(pndd_engine *e, const pndd_handle *roots, size_t root_count,
                   const pndd_handle *candidates, size_t count, unsigned char *mask) {
    pthread_mutex_lock(&admission); int ok = 0;
    PROFILE_START;
    if (begin(e)) {
        for (uint32_t i = 2; i < G->node_high; ++i) { Node *n = node(i); if (n) n->mark = 0; }
        for (size_t i = 0; i < root_count; ++i) {
            uint32_t id = resolve(roots[i]); if (id != INVALID_ID) mark_node(id);
        }
        for (size_t i = 0; i < count; ++i) {
            uint32_t id = resolve(candidates[i]);
            mask[i] = id != INVALID_ID && (id < 2 || node(id)->mark);
        }
        ok = !atomic_load(&G->failure);
    }
    PROFILE_END(3); pthread_mutex_unlock(&admission); return ok;
}
static uint64_t condition_label(uint32_t id, uint32_t field) {
    if (id < 2) return id ? G->fields[field].universe : 0;
    Node *n = node(id);
    if (n->field != field || n->count != 1 || n->edges[0].target != 1) { fail(3); return 0; }
    return n->edges[0].label;
}
static int minimum_label_cost(uint32_t field, uint64_t value, int costly) {
    const int infinity = INT_MAX / 4;
    if (!value) return infinity;
    if (value == truth(field)) return 0;
    pndd_backend mode = G->fields[field].spec.backend;
    if (mode == PNDD_BITSET) {
        Label *l = label(value); int best = infinity;
        unsigned mask = (1u << G->fields[field].spec.width) - 1;
        for (unsigned a = 0; a < (1u << G->widths[mode]); ++a) {
            if ((a < 64 ? l->low >> a : l->high >> (a - 64)) & 1) {
                int cost = __builtin_popcount(costly ? (a & mask) : ((~a) & mask));
                if (cost < best) best = cost;
            }
        }
        return best;
    }
    uint32_t key = 0x20000 + mode * 2 + !!costly; uint64_t cached;
    if (pndd_cache_get(key, value, 0, &cached)) return (int)cached;
    uint32_t var = label_var(field, value);
    int low = minimum_label_cost(field, branch(field, value, var, 0), costly);
    int high = minimum_label_cost(field, branch(field, value, var, 1), costly);
    if (low < infinity && !costly) ++low;
    if (high < infinity && costly) ++high;
    int result = low < high ? low : high;
    pndd_cache_put(key, value, 0, (uint64_t)result); return result;
}
int pndd_min_label_cost(pndd_engine *e, pndd_handle handle, uint32_t field, int costly) {
    pthread_mutex_lock(&admission); int result = -1;
    PROFILE_START;
    if (begin(e)) {
        uint32_t id = resolve(handle);
        if (field >= G->field_count || (G->fields[field].spec.backend == PNDD_ZDD && !costly)) fail(3);
        if (!atomic_load(&G->failure)) result = minimum_label_cost(field, condition_label(id, field), costly);
        if (atomic_load(&G->failure)) result = -1;
    }
    PROFILE_END(5); pthread_mutex_unlock(&admission); return result;
}
typedef struct {
    signed char *bits, *data; size_t count, capacity, limit; uint32_t field, width;
} Cubes;
static void emit_cube(Cubes *c) {
    if (c->count >= c->limit) { fail(3); return; }
    if (c->count == c->capacity) {
        size_t capacity = c->capacity ? c->capacity * 2 : 16;
        if (capacity > c->limit) capacity = c->limit;
        signed char *data = realloc(c->data, capacity * c->width);
        if (!data) { fail(1); return; }
        c->data = data; c->capacity = capacity;
    }
    memcpy(c->data + c->count++ * c->width, c->bits, c->width);
}
static void enumerate_cubes(Cubes *c, uint64_t value) {
    if (!value || atomic_load(&G->failure)) return;
    if (value == truth(c->field)) { emit_cube(c); return; }
    uint32_t var = label_var(c->field, value);
    uint32_t bit = reversed(c->field) ? c->width - 1 - var : var - G->fields[c->field].start;
    if (bit >= c->width) { fail(3); return; }
    for (int high = 0; high <= 1; ++high) {
        c->bits[bit] = (signed char)high;
        enumerate_cubes(c, branch(c->field, value, var, high));
    }
    c->bits[bit] = G->fields[c->field].spec.backend == PNDD_ZDD ? 0 : -1;
}
int pndd_label_cubes(pndd_engine *e, pndd_handle handle, uint32_t field,
                     size_t limit, size_t *count, signed char **cubes) {
    pthread_mutex_lock(&admission); int ok = 0; *count = 0; *cubes = NULL;
    if (begin(e)) {
        uint32_t id = resolve(handle);
        if (field >= G->field_count || !limit || limit > 65536) fail(3);
        if (!atomic_load(&G->failure)) {
            uint64_t value = condition_label(id, field);
            Cubes c = {0}; c.field = field; c.width = G->fields[field].spec.width; c.limit = limit;
            c.bits = malloc(c.width);
            if (!c.bits) fail(1);
            else {
                memset(c.bits, G->fields[field].spec.backend == PNDD_ZDD ? 0 : -1, c.width);
                if (id == 1) { memset(c.bits, -1, c.width); emit_cube(&c); }
                else if (G->fields[field].spec.backend == PNDD_BITSET) {
                    Label *l = value ? label(value) : NULL;
                    for (unsigned a = 0; l && a < (1u << c.width); ++a) {
                        if ((a < 64 ? l->low >> a : l->high >> (a - 64)) & 1) {
                            for (unsigned bit = 0; bit < c.width; ++bit) c.bits[bit] = (a >> (c.width - bit - 1)) & 1;
                            emit_cube(&c);
                        }
                    }
                } else enumerate_cubes(&c, value);
                free(c.bits);
            }
            if (atomic_load(&G->failure)) free(c.data);
            else { *count = c.count; *cubes = c.data; ok = 1; }
        }
    }
    pthread_mutex_unlock(&admission); return ok;
}

static uint64_t rotate(uint64_t value, unsigned n) { return (value << n) | (value >> (64 - n)); }
static uint64_t fingerprint_label(uint32_t field, uint64_t value) {
    if (!value) return mix(0xf00);
    if (value == truth(field)) return mix(0xf01);
    uint32_t key = 0x30000 + G->fields[field].spec.backend; uint64_t result;
    if (pndd_cache_get(key, value, 0, &result)) return result;
    uint32_t var = label_var(field, value);
    if (var == UINT32_MAX) { fail(3); return 0; }
    uint64_t low = fingerprint_label(field, branch(field, value, var, 0));
    uint64_t high = fingerprint_label(field, branch(field, value, var, 1));
    result = mix(var ^ rotate(low, 13) ^ rotate(high, 37) ^ UINT64_C(0xabcdef1234567890));
    pndd_cache_put(key, value, 0, result); return result;
}
static uint64_t fingerprint_node(uint32_t id) {
    if (id < 2) return mix(0xf00 + id);
    uint64_t result;
    if (pndd_cache_get(0x40000, id, 0, &result)) return result;
    Node *n = node(id); uint64_t sum = 0;
    for (size_t i = 0; i < n->count; ++i) {
        uint64_t l = fingerprint_label(n->field, n->edges[i].label);
        uint64_t t = fingerprint_node(n->edges[i].target);
        sum += mix(l ^ rotate(t, 7));
    }
    result = mix(sum ^ ((uint64_t)n->field << 32) ^ n->count);
    pndd_cache_put(0x40000, id, 0, result); return result;
}
int pndd_fingerprint(pndd_engine *e, pndd_handle handle, uint64_t *digest) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        uint32_t id = resolve(handle);
        if (!atomic_load(&G->failure)) { *digest = fingerprint_node(id); ok = !atomic_load(&G->failure); }
    }
    pthread_mutex_unlock(&admission); return ok;
}

static uint32_t queen_lit(uint32_t field, uint32_t bit, int positive) { return G->fields[field].literals[bit * 2 + !!positive]; }
TASK_DECL_3(uint32_t, queen_cell, uint32_t, uint32_t, uint32_t);
TASK_IMPL_3(uint32_t, queen_cell, uint32_t, row, uint32_t, col, uint32_t, n) {
    uint32_t a = 1, b = 1, c = 1, d = 1, source = queen_lit(row, col, 1);
    for (uint32_t j = 0; j < n; ++j) if (j != col)
        a = CALL(apply_rec, PNDD_AND, a, CALL(apply_rec, PNDD_IMP, source, queen_lit(row, j, 0), 0), 0);
    for (uint32_t i = 0; i < n; ++i) if (i != row)
        b = CALL(apply_rec, PNDD_AND, b, CALL(apply_rec, PNDD_IMP, source, queen_lit(i, col, 0), 0), 0);
    for (uint32_t i = 0; i < n; ++i) {
        int j = (int)i - (int)row + (int)col;
        if (i != row && j >= 0 && j < (int)n)
            c = CALL(apply_rec, PNDD_AND, c, CALL(apply_rec, PNDD_IMP, source, queen_lit(i, (uint32_t)j, 0), 0), 0);
    }
    for (uint32_t i = 0; i < n; ++i) {
        int j = (int)row + (int)col - (int)i;
        if (i != row && j >= 0 && j < (int)n)
            d = CALL(apply_rec, PNDD_AND, d, CALL(apply_rec, PNDD_IMP, source, queen_lit(i, (uint32_t)j, 0), 0), 0);
    }
    c = CALL(apply_rec, PNDD_AND, c, d, 0);
    b = CALL(apply_rec, PNDD_AND, b, c, 0);
    return CALL(apply_rec, PNDD_AND, a, b, 0);
}
TASK_DECL_3(double, queen_solve, uint32_t, int, int);
TASK_IMPL_3(double, queen_solve, uint32_t, n, int, compact, int, tree) {
    uint64_t build_start = now_ns();
    size_t capacity = compact ? n + n * (n - 1) / 2 : n + n * n;
    uint32_t *constraints = allocate(capacity * sizeof(uint32_t));
    if (!constraints) return NAN;
    size_t count = 0;
    if (!compact) {
        for (uint32_t i = 0; i < n; ++i) {
            uint32_t row = 0;
            for (uint32_t j = 0; j < n; ++j) row = CALL(apply_rec, PNDD_OR, row, queen_lit(i, j, 1), 0);
            if (row == INVALID_ID) break;
            constraints[count++] = row; ++node(row)->refs;
        }
        for (uint32_t i = 0; i < n && !atomic_load(&G->failure); ++i) for (uint32_t j = 0; j < n && !atomic_load(&G->failure); ++j) {
            uint32_t c = CALL(queen_cell, i, j, n);
            if (c == INVALID_ID) break;
            constraints[count++] = c; ++node(c)->refs;
        }
    } else {
        for (uint32_t i = 0; i < n && !atomic_load(&G->failure); ++i) {
            uint32_t allowed = 0;
            for (uint32_t v = 0; v < n; ++v) allowed = CALL(apply_rec, PNDD_OR, allowed, CALL(construct, i, 0, v, 1), 0);
            if (allowed == INVALID_ID) break;
            constraints[count++] = allowed; ++node(allowed)->refs;
        }
        for (uint32_t i = 0; i < n && !atomic_load(&G->failure); ++i) for (uint32_t j = i + 1; j < n && !atomic_load(&G->failure); ++j) {
            uint32_t forbidden = 0;
            for (uint32_t a = 0; a < n; ++a) for (uint32_t b = 0; b < n; ++b) {
                if (a != b && abs((int)a - (int)b) != (int)(j - i)) continue;
                uint32_t x = CALL(construct, i, 0, a, 1), y = CALL(construct, j, 0, b, 1);
                forbidden = CALL(apply_rec, PNDD_OR, forbidden, CALL(apply_rec, PNDD_AND, x, y, 0), 0);
            }
            uint32_t valid = CALL(apply_rec, PNDD_DIFF, 1, forbidden, 0);
            if (valid == INVALID_ID) break;
            constraints[count++] = valid; ++node(valid)->refs;
        }
    }
    G->build_ns = now_ns() - build_start;
    uint64_t reduce_start = now_ns();
    uint32_t result = atomic_load(&G->failure) ? INVALID_ID : CALL(reduction, PNDD_AND, constraints, count, tree);
    G->reduce_ns = now_ns() - reduce_start;
    uint64_t count_start = now_ns();
    double solutions = atomic_load(&G->failure) ? NAN : satcount_internal(result);
    G->count_ns = now_ns() - count_start;
    for (size_t i = 0; i < count; ++i) --node(constraints[i])->refs;
    deallocate(constraints); return solutions;
}
double pndd_nqueens(pndd_engine *e, uint32_t n, int compact, int tree) {
    pthread_mutex_lock(&admission); double result = NAN;
    if (begin(e)) {
        if (!n || n != G->field_count) fail(3);
        uint32_t width = 1; if (compact) while ((UINT64_C(1) << width) < n) ++width; else width = n;
        for (size_t i = 0; i < G->field_count; ++i) if (G->fields[i].spec.width != width) fail(3);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); result = RUN(queen_solve, n, compact, tree); }
    }
    pthread_mutex_unlock(&admission); return result;
}
