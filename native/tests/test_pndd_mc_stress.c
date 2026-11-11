#include "pndd_mc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x); exit(1); } } while (0)
#define STATES 4096
/* Independent integer BFS; NDD operations do not construct expected sets. */
typedef struct { unsigned char seen[STATES]; unsigned count; } Tuples;
static int visit(void *context, const int *tuple, size_t n) {
    Tuples *v = context; CHECK(n == 3); unsigned id = 0;
    for (unsigned f = 0; f < 3; ++f) { CHECK(tuple[f] >= 0 && tuple[f] < 16); id |= (unsigned)tuple[f] << (4 * f); }
    CHECK(!v->seen[id]); v->seen[id] = 1; ++v->count; return 1;
}
static void verify(pndd_mc_set *s, const unsigned char *expected) {
    Tuples v = {0}; CHECK(pndd_mc_set_enum(s, visit, &v) == 1);
    CHECK(!memcmp(v.seen, expected, STATES)); CHECK(pndd_mc_set_count(s) == v.count);
}
static void bfs(unsigned workers, unsigned mib, unsigned repeat) {
    pndd_mc_config c = pndd_mc_default_config(); c.value_bits = 4;
    c.engine.workers = workers; c.engine.memory_bytes = (size_t)mib << 20;
    c.engine.cache_entries = 64; c.engine.gc_interval = 37;
    c.engine.task_threshold = 2; c.engine.chunk_size = 1; c.engine.label_parallel = 1;
    pndd_mc_domain *d = pndd_mc_open(&c, 3); CHECK(d); CHECK(pndd_mc_collect(d));
    uint64_t baseline = pndd_mc_stats(d).nodes_live;
    pndd_mc_rel *r[3];
    for (uint32_t f = 0; f < 3; ++f) {
        r[f] = pndd_mc_rel_create(d, 1, &f); CHECK(r[f]);
        for (int x = 0; x < 15; ++x) { int y = x + 1; CHECK(pndd_mc_rel_add(r[f], &x, &y)); }
    }
    pndd_mc_set *front = pndd_mc_set_create(d, -1, NULL), *visited = pndd_mc_set_create(d, -1, NULL);
    pndd_mc_set *next = pndd_mc_set_create(d, -1, NULL), *image = pndd_mc_set_create(d, -1, NULL);
    CHECK(front && visited && next && image); int zero[3] = {0}; CHECK(pndd_mc_set_add(front, zero)); CHECK(pndd_mc_set_copy(visited, front));
    unsigned char ef[STATES] = {1}, ev[STATES] = {1}, en[STATES];
    for (unsigned level = 0; level < 46; ++level) {
        verify(front, ef); verify(visited, ev);
        CHECK(pndd_mc_collect(d)); verify(front, ef); verify(visited, ev);
        memset(en, 0, sizeof(en));
        for (unsigned x = 0; x < STATES; ++x) if (ef[x]) for (unsigned f = 0; f < 3; ++f)
            if (((x >> (f * 4)) & 15) < 15) { unsigned y = x + (1u << (f * 4)); if (!ev[y]) en[y] = 1; }
        CHECK(pndd_mc_set_clear(next));
        for (unsigned f = 0; f < 3; ++f) {
            CHECK(pndd_mc_set_next(image, front, r[f])); CHECK(pndd_mc_set_union(next, image));
        }
        /* Exercises the actual exploration operation: visited union next and
         * next minus old visited must be committed together. */
        CHECK(pndd_mc_set_zip(visited, next)); CHECK(pndd_mc_set_copy(front, next));
        memcpy(ef, en, STATES); for (unsigned x = 0; x < STATES; ++x) ev[x] |= en[x];
        verify(front, ef); verify(visited, ev);
    }
    CHECK(pndd_mc_set_empty(front) == 1); CHECK(pndd_mc_set_count(visited) == STATES);
    uint32_t projection[] = {0, 2}; pndd_mc_set *projected = pndd_mc_set_create(d, 2, projection); CHECK(projected);
    CHECK(pndd_mc_set_project(projected, visited)); CHECK(pndd_mc_set_count(projected) == 256);
    pndd_mc_set_destroy(projected); pndd_mc_set_destroy(front); pndd_mc_set_destroy(visited);
    pndd_mc_set_destroy(next); pndd_mc_set_destroy(image);
    for (unsigned f = 0; f < 3; ++f) pndd_mc_rel_destroy(r[f]);
    CHECK(pndd_mc_collect(d)); pndd_stats stats = pndd_mc_stats(d);
    CHECK(stats.nodes_live == baseline); CHECK(stats.collections >= 46 && stats.label_collections >= 46);
    printf("C3 BFS PASS workers=%u MiB=%u repeat=%u states=4096 levels=46 gc=%llu label_gc=%llu peak_native=%llu baseline=%llu\n",
           workers, mib, repeat, (unsigned long long)stats.collections, (unsigned long long)stats.label_collections,
           (unsigned long long)stats.peak_native_bytes, (unsigned long long)baseline);
    CHECK(pndd_mc_close(d));
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    for (unsigned repeat = 0; repeat < 2; ++repeat) { bfs(workers, 4, repeat); bfs(workers, 16, repeat); }
    return 0;
}
