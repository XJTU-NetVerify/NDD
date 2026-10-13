#include "mtpndd.h"
#include "sylvan.h"

#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <inttypes.h>

static void assert_success(mtpndd_error_t err) {
    if (err != MTPNDD_SUCCESS) {
        mtpndd_error_info_t info = mtpndd_get_last_error();
        fprintf(stderr, "MTPNDD error %d (%s) at %s:%d\n",
                err, mtpndd_error_string(err), info.function ? info.function : "?", info.line);
        assert(false);
    }
}

static mtpndd_t *checked_node(mtpndd_t *node) {
    if (!node) {
        mtpndd_error_info_t info = mtpndd_get_last_error();
        fprintf(stderr, "MTPNDD op failed (%s) at %s:%d\n",
                mtpndd_error_string(info.code), info.function ? info.function : "?", info.line);
        abort();
    }
    return node;
}

// Generate a random IPv4 address with each octet in range 0-255.
static uint32_t generate_random_ip(void) {
    uint32_t ip = 0;
    for (int i = 0; i < 4; ++i) {
        uint8_t octet = (uint8_t)(rand() % 256);
        ip |= ((uint32_t)octet) << (8 * i);
    }
    return ip;
}

static void print_ip(uint32_t ip) {
    printf("%u.%u.%u.%u",
           (ip >> 24) & 0xFFu,
           (ip >> 16) & 0xFFu,
           (ip >> 8) & 0xFFu,
           ip & 0xFFu);
}

static void print_ip_list(const char *label, const uint32_t *ips, size_t count) {
    printf(">> %s: ", label);
    for (size_t i = 0; i < count; ++i) {
        print_ip(ips[i]);
        if (i + 1 != count) {
            printf(", ");
        }
    }
    printf("\n");
}

static mtpndd_bdd_t build_octet_bdd(uint32_t field_id, uint8_t octet) {
    // Build an exact-match BDD for a single 8-bit field.
    mtpndd_bdd_t acc = sylvan_ref(sylvan_true);
    for (uint32_t bit = 0; bit < 8; ++bit) {
        mtpndd_bdd_t literal = (octet & (1u << bit))
                ? sylvan_ref(mtpndd_get_bdd_var(field_id, bit))
                : sylvan_ref(mtpndd_get_bdd_not_var(field_id, bit));
        mtpndd_bdd_t next = sylvan_ref(sylvan_and(acc, literal));
        sylvan_deref(acc);
        sylvan_deref(literal);
        acc = next;
    }
    return acc;
}

static mtpndd_bdd_t build_ip_exact_bdd(const uint32_t field_ids[4], uint32_t ip) {
    // Split a 32-bit IP into four 8-bit fields and combine into a single BDD.
    mtpndd_bdd_t acc = sylvan_ref(sylvan_true);
    for (int i = 0; i < 4; ++i) {
        uint8_t octet = (ip >> (8 * i)) & 0xFFu;
        mtpndd_bdd_t oct_bdd = build_octet_bdd(field_ids[i], octet);
        mtpndd_bdd_t next = sylvan_ref(sylvan_and(acc, oct_bdd));
        sylvan_deref(acc);
        sylvan_deref(oct_bdd);
        acc = next;
    }
    return acc;
}

static mtpndd_bdd_t build_ip_set_bdd(const uint32_t field_ids[4], const uint32_t *ips, size_t count) {
    // Build an exact-match BDD for each IP and OR them into a set BDD.
    mtpndd_bdd_t acc = sylvan_ref(sylvan_false);
    for (size_t i = 0; i < count; ++i) {
        mtpndd_bdd_t single = build_ip_exact_bdd(field_ids, ips[i]);
        mtpndd_bdd_t next = sylvan_ref(sylvan_or(acc, single));
        sylvan_deref(acc);
        sylvan_deref(single);
        acc = next;
    }
    return acc;
}

// TODO: 1. check parallel
// TODO: 2. check multi-terminal
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
static void print_recording_stats(void) {
    const mtpndd_stats_t *stats = mtpndd_get_stats();
    if (!stats) {
        return;
    }
    printf(">> MTPNDD stats:\n");
    printf("   nodes_created_total    = %" PRIu64 "\n", stats->nodes_created_total);
    printf("   nodes_reused_total     = %" PRIu64 "\n", stats->nodes_reused_total);
    printf("   nodes_collected_last   = %" PRIu64 "\n", stats->nodes_collected_last);
    printf("   node_pool_acquire_total= %" PRIu64 "\n", stats->node_pool_acquire_total);
    printf("   edge_entry_pool_acquire= %" PRIu64 "\n", stats->edge_entry_pool_acquire_total);
    printf("   max_edges_per_node     = %" PRIu64 "\n", stats->max_edges_per_node);
    printf("   cache hits/misses      = %" PRIu64 " / %" PRIu64 "\n",
           stats->cache_lookup_hits, stats->cache_lookup_misses);
}
#endif

int main(void) {
    mtpndd_pal_config_t config = {
        .n_workers = 0,
        .lace_dqsize = 1 << 18,
        .bdd_nodetable_size = 1 << 16,
        .mtpndd_nodetable_size = 1 << 14,
        .op_cache_size = 1 << 12,
        .edge_bucket_count = 32,
        .nodetable_bucket_count = 1 << 12,
        .node_slab_capacity = 1024,
        .edge_entry_slab_capacity = 4096,
        .nodetable_entry_slab_capacity = 2048,
        .edge_map_slab_capacity = 1024,
    };
    assert_success(mtpndd_init(&config));
    printf(">> mtpndd_init succeeded\n");

    uint32_t ip_field_ids[4];
    for (int i = 0; i < 4; ++i) {
        assert_success(mtpndd_declare_field(8));
        ip_field_ids[i] = (uint32_t)(i + 1);
    }

    srand(42);
    const size_t ip_set_size = 32;
    uint32_t ip_set_a[ip_set_size];
    uint32_t ip_set_b[ip_set_size];

    for (size_t i = 0; i < ip_set_size; ++i) {
        ip_set_a[i] = generate_random_ip();
        ip_set_b[i] = generate_random_ip();
    }

    print_ip_list("IP group A", ip_set_a, ip_set_size);
    print_ip_list("IP group B", ip_set_b, ip_set_size);

    mtpndd_bdd_t ip_a_bdd = build_ip_set_bdd(ip_field_ids, ip_set_a, ip_set_size);
    mtpndd_bdd_t ip_b_bdd = build_ip_set_bdd(ip_field_ids, ip_set_b, ip_set_size);

    mtpndd_t *ip_a_node = NULL;
    mtpndd_t *ip_b_node = NULL;
    assert_success(mtbdd_to_mtpndd(ip_a_bdd, &ip_a_node));
    assert_success(mtbdd_to_mtpndd(ip_b_bdd, &ip_b_node));

    // Roundtrip check: converting back to MTBDD must yield the original BDD.
    mtpndd_bdd_t ip_a_back = sylvan_false;
    mtpndd_bdd_t ip_b_back = sylvan_false;
    assert_success(mtpndd_to_mtbdd(ip_a_node, &ip_a_back));
    assert_success(mtpndd_to_mtbdd(ip_b_node, &ip_b_back));
    assert(ip_a_back == ip_a_bdd);
    assert(ip_b_back == ip_b_bdd);
    sylvan_deref(ip_a_back);
    sylvan_deref(ip_b_back);
    printf(">> IP set roundtrip ok\n");

    mtpndd_bdd_t expected_union = sylvan_ref(sylvan_or(ip_a_bdd, ip_b_bdd));
    mtpndd_bdd_t expected_intersection = sylvan_ref(sylvan_and(ip_a_bdd, ip_b_bdd));
    mtpndd_bdd_t not_ip_b = sylvan_ref(sylvan_not(ip_b_bdd));
    mtpndd_bdd_t expected_difference = sylvan_ref(sylvan_and(ip_a_bdd, not_ip_b));
    sylvan_deref(not_ip_b);

    mtpndd_t *union_node = checked_node(mtpndd_or(ip_a_node, ip_b_node));
    mtpndd_t *intersection_node = checked_node(mtpndd_and(ip_a_node, ip_b_node));
    mtpndd_t *not_ip_b_node = checked_node(mtpndd_not(ip_b_node));
    mtpndd_t *difference_node = checked_node(mtpndd_and(ip_a_node, not_ip_b_node));

    mtpndd_bdd_t union_back = sylvan_false;
    mtpndd_bdd_t intersection_back = sylvan_false;
    mtpndd_bdd_t difference_back = sylvan_false;
    assert_success(mtpndd_to_mtbdd(union_node, &union_back));
    assert_success(mtpndd_to_mtbdd(intersection_node, &intersection_back));
    assert_success(mtpndd_to_mtbdd(difference_node, &difference_back));
    assert(union_back == expected_union);
    assert(intersection_back == expected_intersection);
    assert(difference_back == expected_difference);
    sylvan_deref(union_back);
    sylvan_deref(intersection_back);
    sylvan_deref(difference_back);
    printf(">> union/intersection/difference ok\n");

    sylvan_deref(expected_union);
    sylvan_deref(expected_intersection);
    sylvan_deref(expected_difference);
    sylvan_deref(ip_a_bdd);
    sylvan_deref(ip_b_bdd);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    print_recording_stats();
#endif

    assert_success(mtpndd_quit());
    printf(">> mtpndd_quit succeeded\n");
    return 0;
}
