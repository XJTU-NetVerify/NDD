#include "pmtndd.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
static pndd_handle checked(pndd_engine *e, pndd_handle h) {
    if (h == PNDD_INVALID) fprintf(stderr, "%s\n", pndd_error(e));
    assert(h != PNDD_INVALID); return h;
}
static pndd_handle apply(pndd_engine *e, pmt_op op, pndd_handle a, pndd_handle b) { return checked(e, pmt_apply(e, op, a, b)); }
static void release(pndd_engine *e, pndd_handle h) { assert(pndd_release(e, h)); }
static double eval(pndd_engine *e, pndd_handle h, uint64_t a, uint64_t b) {
    uint64_t assignment[] = {a, b}; pmt_number v;
    assert(pmt_evaluate(e, h, assignment, 2, &v)); return v.value;
}
static void run(unsigned workers, int backend, int mixed, pmt_domain domain) {
    pndd_config c = pndd_default_config(); c.workers = workers;
    c.gc_interval = 5; c.cache_entries = 64; c.memory_bytes = 16 * 1024 * 1024;
    c.task_threshold = 2; c.chunk_size = 1;
    pndd_field fields[] = {{3, backend}, {2, mixed ? (backend + 1) % 4 : backend}};
    pndd_engine *e = pmt_open(&c, fields, 2, domain); assert(e);
    pndd_handle xbit = checked(e, pndd_literal(e, 0, 0, 1)), ybit = checked(e, pndd_literal(e, 1, 1, 0));
    for (pmt_op op = PMT_AND; op <= PMT_NOR; ++op) {
        pndd_handle predicate = apply(e, op, xbit, ybit);
        for (unsigned a = 0; a < 8; ++a) for (unsigned b = 0; b < 4; ++b) {
            int x = (a & 4) != 0, y = (b & 1) == 0;
            int expected = op == PMT_AND ? x && y : op == PMT_OR ? x || y : op == PMT_DIFF ? x && !y : op == PMT_XOR ? x != y : op == PMT_IMP ? !x || y : op == PMT_EQUIV ? x == y : op == PMT_NAND ? !(x && y) : !(x || y);
            assert(eval(e, predicate, a, b) == expected);
        }
        release(e, predicate);
    }
    pndd_handle xy = apply(e, PMT_AND, xbit, ybit);
    pndd_handle boolean_product = apply(e, PMT_TIMES, xbit, ybit);
    assert(boolean_product == xy);
    pndd_handle fused_boolean = checked(e, pmt_multiply_sum(e, xbit, ybit, 1));
    pndd_handle separate_boolean = checked(e, pmt_sum_abstract(e, xy, 1));
    assert(fused_boolean == separate_boolean);
    /* Two satisfying y assignments: Boolean fusion must retain multiplicity. */
    assert(eval(e, fused_boolean, 4, 0) == 2);
    release(e, boolean_product); release(e, fused_boolean); release(e, separate_boolean);
    pndd_handle projected = checked(e, pmt_exist(e, xy, 1));
    assert(projected == xbit); release(e, projected); release(e, xy); release(e, xbit); release(e, ybit);
    pndd_handle f = checked(e, pndd_constant(e, 0)), g = checked(e, pndd_constant(e, 0));
    for (unsigned a = 0; a < 8; ++a) for (unsigned b = 0; b < 4; ++b) {
        pndd_handle x = checked(e, pndd_value(e, 0, a)), y = checked(e, pndd_value(e, 1, b));
        pndd_handle cube = apply(e, PMT_AND, x, y); release(e, x); release(e, y);
        pndd_handle n = domain == PMT_RATIONAL ? checked(e, pmt_fraction(e, a * 4 + b + 1, 2)) : checked(e, pmt_double(e, (a * 4 + b + 1) / 2.0));
        pndd_handle m = domain == PMT_RATIONAL ? checked(e, pmt_fraction(e, 64 - a * 4 - b, 3)) : checked(e, pmt_double(e, (64 - a * 4 - b) / 3.0));
        pndd_handle fc = apply(e, PMT_TIMES, cube, n), gc = apply(e, PMT_TIMES, cube, m);
        pndd_handle nf = apply(e, PMT_PLUS, f, fc), ng = apply(e, PMT_PLUS, g, gc);
        release(e, f); release(e, g); f = nf; g = ng;
        release(e, n); release(e, m); release(e, fc); release(e, gc); release(e, cube);
        assert(pndd_collect(e));
    }
    pmt_number dense[32];
    for (unsigned i = 0; i < 32; ++i) dense[i] = (pmt_number){i + 1, 2, (i + 1) / 2.0, domain};
    pndd_handle imported = checked(e, pmt_from_array(e, dense, 32)); assert(imported == f); release(e, imported);
    assert(pmt_from_array(e, dense, 31) == PNDD_INVALID);
    if (domain == PMT_RATIONAL) {
        dense[31].denominator = 0; assert(pmt_from_array(e, dense, 32) == PNDD_INVALID);
        assert(pndd_collect(e));
    }
    assert(pmt_multiply_sum(e, f, g, UINT32_MAX) == PNDD_INVALID);
    assert(pmt_satcount(e, f) == 32);
    pmt_number maximum;
    assert(pmt_max_terminal(e,f,&maximum) && maximum.value==16);
    pndd_handle seventeen=domain==PMT_RATIONAL?checked(e,pmt_fraction(e,17,1)):checked(e,pmt_double(e,17));
    pndd_handle all_negative=apply(e,PMT_MINUS,f,seventeen);
    assert(pmt_max_terminal(e,all_negative,&maximum) && maximum.value==-1);
    release(e,seventeen);release(e,all_negative);

    for (pmt_op op = PMT_PLUS; op <= PMT_DIVIDE; ++op) {
        pndd_handle result = apply(e, op, f, g);
        assert(pndd_collect(e));
        for (unsigned a = 0; a < 8; ++a) for (unsigned b = 0; b < 4; ++b) {
            double x = (a * 4 + b + 1) / 2.0, y = (64 - a * 4 - b) / 3.0;
            double expected = op == PMT_PLUS ? x + y : op == PMT_MINUS ? x - y : op == PMT_TIMES ? x * y : x / y;
            assert(fabs(eval(e, result, a, b) - expected) < 1e-11);
        }
        release(e, result);
    }
    for (unsigned field = 0; field < 2; ++field) {
        pndd_handle product = apply(e, PMT_TIMES, f, g);
        pndd_handle separate = checked(e, pmt_sum_abstract(e, product, field));
        pndd_handle fused = checked(e, pmt_multiply_sum(e, f, g, field));
        if (domain == PMT_RATIONAL) assert(separate == fused);
        release(e, product);
        for (unsigned a = 0; a < 8; ++a) for (unsigned b = 0; b < 4; ++b) {
            double expected = 0;
            for (unsigned i = 0; i < (field ? 4u : 8u); ++i) {
                unsigned k = (field ? a : i) * 4 + (field ? i : b);
                expected += ((k + 1) / 2.0) * ((64 - k) / 3.0);
            }
            assert(fabs(eval(e, fused, a, b) - expected) < 1e-9);
        }
        release(e, separate); release(e, fused);
    }
    pndd_handle sum = checked(e, pmt_sum_abstract(e, f, 0));
    pndd_handle total = checked(e, pmt_sum_abstract(e, sum, 1)); pmt_number number;
    assert(pmt_terminal(e, total, &number) && number.value == 264);
    release(e, sum); release(e, total);
    pndd_handle aa[32], bb[32], out[32]; for (unsigned i = 0; i < 32; ++i) { aa[i] = f; bb[i] = g; }
    uint64_t runs = pndd_get_stats(e).external_runs;
    /* gc_interval disabled for measuring admission RUN count, not performance. */
    assert(pmt_apply_batch(e, PMT_PLUS, aa, bb, 32, out));
    uint64_t after = pndd_get_stats(e).external_runs; assert(after - runs >= 1 && after - runs <= 2);
    for (unsigned i = 0; i < 32; ++i) release(e, out[i]);
    pndd_handle l = checked(e, pmt_reduce(e, PMT_PLUS, aa, 32, 0)), t = checked(e, pmt_reduce(e, PMT_PLUS, aa, 32, 1));
    if (domain == PMT_RATIONAL) assert(l == t);
    assert(fabs(eval(e, l, 1, 1) - eval(e, t, 1, 1)) < 1e-10);
    release(e, l); release(e, t);
    pndd_handle zero = checked(e, pndd_constant(e, 0));
    assert(pmt_apply(e, PMT_DIVIDE, f, zero) == PNDD_INVALID);
    /* Batch error after other tasks completed returns no owned partial roots. */
    bb[31] = zero; assert(!pmt_apply_batch(e, PMT_DIVIDE, aa, bb, 32, out));
    for (unsigned i = 0; i < 32; ++i) assert(out[i] == PNDD_INVALID);
    release(e, zero);
    pndd_handle stale = checked(e, pndd_retain(e, f)); release(e, stale);
    release(e, f); release(e, g); assert(pndd_collect(e));
    assert(pndd_retain(e, stale) == PNDD_INVALID);
    pndd_stats stats = pndd_get_stats(e); assert(stats.collections > 10);
    if (workers > 1) assert(stats.tasks > 0);
    pndd_close(e);
}
static void pressure(unsigned workers, pndd_backend backend) {
    pndd_field field = {16, backend}; pndd_config c = pndd_default_config();
    c.workers = workers; c.memory_bytes = 64u * 1024 * 1024; c.cache_entries = 4096;
    c.gc_interval = 0; c.label_parallel = 1; c.task_threshold = 2; c.chunk_size = 1;
    pndd_engine *e = pmt_open(&c, &field, 1, PMT_RATIONAL); assert(e);
    enum { PREFIXES = 256, SUFFIXES = 128, CASES = PREFIXES * SUFFIXES };
    pndd_handle prefix[PREFIXES], suffix[SUFFIXES];
    pndd_handle a[CASES], b[CASES], out[CASES];
    /* Worker-local allocation regions can grow the table during construction,
     * leaving enough space for the old adjacent-point OR batch without a GC.
     * Instead build small sets of half-word cubes, then materialize their full
     * Cartesian product inside one batch. Its fresh labels outgrow the initial
     * Sylvan table while remaining within the configured maximum capacity. */
    for (unsigned half = 0; half < 2; ++half) {
        unsigned count = half ? SUFFIXES : PREFIXES;
        pndd_handle *parts = half ? suffix : prefix;
        for (unsigned value = 0; value < count; ++value) {
            pndd_handle root = pndd_constant(e, 1);
            for (unsigned bit = 0; bit < 8; ++bit) {
                pndd_handle literal = pndd_literal(e, 0, half * 8 + bit, (value >> (7 - bit)) & 1);
                pndd_handle next = pmt_apply(e, PMT_AND, root, literal);
                assert(next != PNDD_INVALID);
                pndd_release(e, root); pndd_release(e, literal); root = next;
            }
            parts[value] = root;
        }
    }
    pndd_handle weight = checked(e, pmt_fraction(e, 1, 3));
    for (unsigned i = 0; i < PREFIXES; ++i) {
        pndd_handle next = apply(e, PMT_TIMES, prefix[i], weight);
        release(e, prefix[i]); prefix[i] = next;
    }
    release(e, weight);
    for (unsigned i = 0; i < CASES; ++i) { a[i] = prefix[i / SUFFIXES]; b[i] = suffix[i % SUFFIXES]; }
    for (unsigned round = 0; round < 2; ++round) {
        uint64_t before = pndd_get_stats(e).label_collections;
        assert(pmt_apply_batch(e, PMT_TIMES, a, b, CASES, out));
        if (round == 0) assert(pndd_get_stats(e).label_collections > before);
        for (unsigned i = 0; i < CASES; ++i) {
            assert(pmt_satcount(e, out[i]) == 1);
            if (round == 0) {
                uint64_t value = ((uint64_t)(i / SUFFIXES) << 8) | (i % SUFFIXES);
                pmt_number number; assert(pmt_evaluate(e, out[i], &value, 1, &number));
                assert(number.numerator == 1 && number.denominator == 3);
            }
            assert(pndd_release(e, out[i]));
        }
        assert(pndd_collect(e));
    }
    for (unsigned i = 0; i < PREFIXES; ++i) pndd_release(e, prefix[i]);
    for (unsigned i = 0; i < SUFFIXES; ++i) pndd_release(e, suffix[i]);
    assert(pndd_collect(e)); assert(pndd_get_stats(e).label_collections > 0); pndd_close(e);
}
static void boundary_checks(unsigned workers) {
    pndd_config c = pndd_default_config(); c.workers = workers; c.memory_bytes = 4u << 20; c.cache_entries = 64; c.gc_interval = 0;
    pndd_field field = {54, PNDD_BCDD}; pndd_engine *e = pmt_open(&c, &field, 1, PMT_RATIONAL); assert(e);
    pndd_handle zero = checked(e, pndd_value(e, 0, 0)), one = checked(e, pndd_constant(e, 1));
    pndd_handle complement = apply(e, PMT_DIFF, one, zero);
    pndd_handle total = checked(e, pmt_sum_abstract(e, complement, 0)); pmt_number result;
    assert(pmt_terminal(e, total, &result));
    assert(result.numerator == INT64_C(18014398509481983) && result.denominator == 1);
    release(e, zero); release(e, one); release(e, complement); release(e, total);
    pndd_handle scalar = checked(e, pmt_fraction(e, 1, 3));
    enum { COUNT = 262144 };
    pndd_handle *inputs = malloc(COUNT * sizeof(*inputs)), *outputs = malloc(COUNT * sizeof(*outputs)); assert(inputs && outputs);
    for (unsigned i = 0; i < COUNT; ++i) { inputs[i] = scalar; outputs[i] = 0; }
    assert(!pmt_apply_batch(e, PMT_PLUS, inputs, inputs, COUNT, outputs));
    for (unsigned i = 0; i < COUNT; ++i) assert(outputs[i] == PNDD_INVALID);
    assert(pndd_collect(e)); assert(pmt_terminal(e, scalar, &result)); assert(result.numerator == 1 && result.denominator == 3);
    free(inputs); free(outputs); release(e, scalar); pndd_close(e);
    field = (pndd_field){1200, PNDD_BCDD}; e = pmt_open(&c, &field, 1, PMT_DOUBLE); assert(e);
    pndd_handle empty = checked(e, pndd_constant(e, 0));
    assert(pmt_satcount(e, empty) == 0);
    pndd_handle abstracted = checked(e, pmt_sum_abstract(e, empty, 0));
    assert(pmt_terminal(e, abstracted, &result) && result.value == 0);
    release(e, abstracted); release(e, empty); pndd_close(e);
}
static void application_api_checks(unsigned workers) {
    pndd_config c=pndd_default_config();c.workers=workers;c.memory_bytes=16u<<20;c.cache_entries=64;
    for (int backend=0;backend<4;++backend) for(int reverse=0;reverse<2;++reverse) {
        c.reverse_labels=reverse;
        pndd_field fields[]={{backend==PNDD_BITSET?7:70,backend},{2,backend}};
        pndd_engine *e=pmt_open(&c,fields,2,PMT_RATIONAL);assert(e);
        pndd_handle high=checked(e,pndd_literal(e,0,0,1)),half=checked(e,pmt_fraction(e,1,2));
        pndd_handle weighted=apply(e,PMT_TIMES,high,half),negative=checked(e,pmt_fraction(e,-1,3));
        pndd_handle partial=apply(e,PMT_TIMES,high,negative);pmt_number value;
        assert(pmt_max_terminal(e,partial,&value)&&value.numerator==0);
        assert(pmt_max_terminal(e,negative,&value)&&value.numerator==-1&&value.denominator==3);
        assert(pmt_max_terminal(e,weighted,&value)&&value.numerator==1&&value.denominator==2);
        unsigned char bits[72]={0};size_t size=fields[0].width+2;
        assert(pmt_evaluate_bits(e,weighted,bits,size,&value)&&value.numerator==0);
        bits[0]=1;assert(pmt_evaluate_bits(e,weighted,bits,size,&value)&&value.numerator==1&&value.denominator==2);
        bits[0]=2;assert(!pmt_evaluate_bits(e,weighted,bits,size,&value));bits[0]=1;
        pndd_handle a[]={weighted,partial},b[]={half,half},out[2];
        uint64_t runs=pndd_get_stats(e).external_runs;
        assert(pmt_sum_abstract_batch(e,a,2,1,out));assert(pndd_get_stats(e).external_runs==runs+1);
        for(unsigned i=0;i<2;++i){pndd_handle scalar=checked(e,pmt_sum_abstract(e,a[i],1));assert(out[i]==scalar);release(e,scalar);release(e,out[i]);}
        assert(pmt_multiply_sum_batch(e,a,b,2,1,out));
        for(unsigned i=0;i<2;++i){pndd_handle scalar=checked(e,pmt_multiply_sum(e,a[i],b[i],1));assert(out[i]==scalar);release(e,scalar);release(e,out[i]);}
        assert(pmt_sum_abstract_batch(e,NULL,0,1,NULL));
        if(backend!=PNDD_BITSET){assert(!pmt_sum_abstract_batch(e,a,2,0,out));assert(out[0]==PNDD_INVALID&&out[1]==PNDD_INVALID);assert(pndd_collect(e));assert(pmt_evaluate_bits(e,weighted,bits,size,&value));}
        release(e,high);release(e,half);release(e,weighted);release(e,negative);release(e,partial);pndd_close(e);
    }
}
static void idle_admissions(unsigned workers) {
    pndd_config c=pndd_default_config();c.workers=workers;c.gc_interval=0;
    c.memory_bytes=16u<<20;c.cache_entries=64;
    pndd_field field={2,PNDD_BDD};
    pndd_engine *e=pmt_open(&c,&field,1,PMT_RATIONAL);assert(e);
    pndd_handle bit=checked(e,pndd_literal(e,0,0,1));
    struct timespec pause={0,5000000};
    for(int i=0;i<24;++i) {
        nanosleep(&pause,NULL); /* force an idle coordinator, including worker=1 */
        uint64_t runs=pndd_get_stats(e).external_runs;
        pndd_handle sum=checked(e,pmt_sum_abstract(e,bit,0));pmt_number value;
        assert(pndd_get_stats(e).external_runs==runs+1);
        assert(pmt_terminal(e,sum,&value)&&value.numerator==2&&value.denominator==1);
        release(e,sum);
        if(i%6==0)assert(pndd_collect(e));
    }
    release(e,bit);nanosleep(&pause,NULL);pndd_close(e);
}
static void disjoint_numeric_jobs(unsigned workers) {
    pndd_config c=pndd_default_config();c.workers=workers;c.gc_interval=0;
    c.memory_bytes=16u<<20;c.cache_entries=64;c.chunk_size=1;c.task_threshold=2;
    pndd_field field={2,PNDD_BDD};
    pndd_engine *e=pmt_open(&c,&field,1,PMT_RATIONAL);assert(e);
    pndd_handle hi=checked(e,pndd_literal(e,0,0,1)),lo=checked(e,pndd_literal(e,0,0,0));
    pndd_handle half=checked(e,pmt_fraction(e,1,2)),third=checked(e,pmt_fraction(e,1,3));
    pndd_handle a=apply(e,PMT_TIMES,hi,half),b=apply(e,PMT_TIMES,lo,third);
    uint64_t tasks=pndd_get_stats(e).tasks;
    pndd_handle sum=apply(e,PMT_PLUS,a,b);
#ifdef PNDD_COST_GRAIN
    assert(pndd_get_stats(e).tasks==tasks); /* only x+0/0+y jobs: no useful tasks */
#else
    (void)tasks;
#endif
    uint64_t values[]={0};pmt_number value;
    assert(pmt_evaluate(e,sum,values,1,&value)&&value.numerator==1&&value.denominator==3);
    values[0]=2;
    assert(pmt_evaluate(e,sum,values,1,&value)&&value.numerator==1&&value.denominator==2);
    assert(pndd_collect(e));
    pndd_handle total=checked(e,pmt_sum_abstract(e,sum,0));
    assert(pmt_terminal(e,total,&value)&&value.numerator==5&&value.denominator==3);
    release(e,total);release(e,sum);release(e,a);release(e,b);release(e,hi);release(e,lo);release(e,half);release(e,third);
    pndd_close(e);
}
static void disjoint_linear_budget(unsigned workers) {
#ifdef PNDD_NUMERIC_DISJOINT
    pndd_config c=pndd_default_config();c.workers=workers;c.gc_interval=0;
    c.memory_bytes=16u<<20;c.cache_entries=64;
    pndd_field field={11,PNDD_BDD};
    pndd_engine *e=pmt_open(&c,&field,1,PMT_RATIONAL);assert(e);
    pmt_number values[2048];
    for(unsigned i=0;i<2048;++i)values[i]=(pmt_number){i<1024?i+1:0,1,0,PMT_RATIONAL};
    pndd_handle left=checked(e,pmt_from_array(e,values,2048));
    for(unsigned i=0;i<2048;++i)values[i].numerator=i>=1024?i+1:0;
    pndd_handle right=checked(e,pmt_from_array(e,values,2048));
    /* The old Cartesian scratch exceeds 40 MiB; a linear union fits 16 MiB. */
    pndd_handle sum=apply(e,PMT_PLUS,left,right);pmt_number value;
    uint64_t assignment[]={1023};
    assert(pmt_evaluate(e,sum,assignment,1,&value)&&value.numerator==1024&&value.denominator==1);
    assignment[0]=1024;
    assert(pmt_evaluate(e,sum,assignment,1,&value)&&value.numerator==1025&&value.denominator==1);
    assert(pndd_collect(e));assert(pmt_max_terminal(e,sum,&value)&&value.numerator==2048);
    release(e,left);release(e,right);release(e,sum);pndd_close(e);
#else
    (void)workers;
#endif
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? atoi(argv[1]) : 1;
    if(argc>2) { idle_admissions(workers); return 0; }
    for (int backend = 0; backend < 4; ++backend) for (int mixed = 0; mixed < 2; ++mixed)
        for (pmt_domain domain = PMT_RATIONAL; domain <= PMT_DOUBLE; ++domain) run(workers, backend, mixed, domain);
    application_api_checks(workers);
    idle_admissions(workers);
    disjoint_numeric_jobs(workers);
    disjoint_linear_budget(workers);
    boundary_checks(workers);
    pressure(workers, PNDD_BCDD); pressure(workers, PNDD_ZDD);
    puts("MT multi-backend arithmetic, fused contraction, owned roots and GC passed");
    return 0;
}
