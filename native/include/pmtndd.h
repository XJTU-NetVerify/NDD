#ifndef PMTNDD_H
#define PMTNDD_H
#include "pndd.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Scalar domain belongs to the engine. Rational uses normalized signed int64
 * numerator/positive int64 denominator, checked __int128 intermediates.
 * Double mode rejects nonfinite inputs/results and canonicalizes signed zero. */
typedef enum { PMT_RATIONAL, PMT_DOUBLE } pmt_domain;
typedef enum { PMT_AND, PMT_OR, PMT_DIFF, PMT_XOR, PMT_IMP, PMT_EQUIV,
               PMT_NAND, PMT_NOR, PMT_PLUS, PMT_MINUS, PMT_TIMES, PMT_DIVIDE } pmt_op;
typedef struct { int64_t numerator, denominator; double value; pmt_domain domain; } pmt_number;
pndd_engine *pmt_open(const pndd_config *, const pndd_field *, size_t, pmt_domain);
pndd_handle pmt_fraction(pndd_engine *, int64_t, int64_t);
pndd_handle pmt_double(pndd_engine *, double);
pndd_handle pmt_apply(pndd_engine *, pmt_op, pndd_handle, pndd_handle);
pndd_handle pmt_sum_abstract(pndd_engine *, pndd_handle, uint32_t);
/* Fused contraction: avoids materializing the product at/above eliminated field. */
pndd_handle pmt_multiply_sum(pndd_engine *, pndd_handle, pndd_handle, uint32_t);
pndd_handle pmt_exist(pndd_engine *, pndd_handle, uint32_t);
int pmt_evaluate(pndd_engine *, pndd_handle, const uint64_t *, size_t, pmt_number *);
int pmt_terminal(pndd_engine *, pndd_handle, pmt_number *);
double pmt_satcount(pndd_engine *, pndd_handle);
/* All results own roots; batch uses exactly one external RUN. On failure out
 * is invalid and no partial ownership escapes (valid output buffer required).
 * Output must not alias input arrays. Inputs remain borrowed. */
int pmt_apply_batch(pndd_engine *, pmt_op, const pndd_handle *, const pndd_handle *, size_t, pndd_handle *);
/* Dense importer: field 0 varies slowest, last field fastest. One RUN,
 * no wrapper/root per terminal; input is borrowed for the whole call. */
pndd_handle pmt_from_array(pndd_engine *, const pmt_number *, size_t);
pndd_handle pmt_reduce(pndd_engine *, pmt_op, const pndd_handle *, size_t, int);
/* Flattened MSB-first bits, one byte per bit, fields concatenated. */
int pmt_evaluate_bits(pndd_engine *, pndd_handle, const unsigned char *, size_t, pmt_number *);
int pmt_max_terminal(pndd_engine *, pndd_handle, pmt_number *);
int pmt_sum_abstract_batch(pndd_engine *, const pndd_handle *, size_t, uint32_t, pndd_handle *);
int pmt_multiply_sum_batch(pndd_engine *, const pndd_handle *, const pndd_handle *, size_t, uint32_t, pndd_handle *);
#ifdef __cplusplus
}
#endif
#endif
