#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>

#include "mmio.h"
#include "iocache_regs.h"

/* ---- MMIO helpers ---- */
uint64_t do_read(uintptr_t addr, int size_bits) {
    switch (size_bits) {
    case 1:
    case 8:   return reg_read8(addr);
    case 16:  return reg_read16(addr);
    case 32:  return reg_read32(addr);
    case 64:  return reg_read64(addr);
    default:  return 0;
    }
}
void do_write(uintptr_t addr, int size_bits, uint64_t val) {
    switch (size_bits) {
    case 1:
    case 8:   reg_write8(addr,  (uint8_t)val);  break;
    case 16:  reg_write16(addr, (uint16_t)val); break;
    case 32:  reg_write32(addr, (uint32_t)val); break;
    case 64:  reg_write64(addr, val);           break;
    default:  break;
    }
}
static inline uint64_t mask_bits(unsigned bits) {
    return (bits >= 64) ? ~0ULL : ((1ULL << bits) - 1ULL);
}

/* ---- Convenience programming ---- */
static void prog_row(unsigned row, uint32_t cpu, uint64_t ptr, bool enable)
{
    do_write(IOCACHE_PROC_CPU(row), 32, cpu);
    do_write(IOCACHE_PROC_PTR(row), 64, ptr);
    do_write(IOCACHE_ENABLED(row),   1, enable ? 1 : 0);
}

/* Also set RX/Tx suspend/avail bits explicitly when needed */
static void set_rx_state(unsigned row, bool avail, bool suspended)
{
    do_write(IOCACHE_RX_AVAILABLE(row), 1, avail ? 1 : 0);
    do_write(IOCACHE_RX_SUSPENDED(row), 1, suspended ? 1 : 0);
}
static void set_txc_state(unsigned row, bool avail, bool suspended)
{
    do_write(IOCACHE_TXCOMP_AVAILABLE(row), 1, avail ? 1 : 0);
    do_write(IOCACHE_TXCOMP_SUSPENDED(row), 1, suspended ? 1 : 0);
}

/* ==== Test 1: Per-CPU fair scheduler (RR) ==== */
static int test_fair_sched_round_robin_per_cpu(uint32_t cpu)
{
    puts("\n== Fair scheduler test (per-CPU) ==");

    /* Clear a few rows first */
    prog_row(0, 0, 0, false);
    prog_row(5, 0, 0, false);
    prog_row(9, 0, 0, false);

    const uint64_t ptr0 = 0xAAA0000000000000ULL | 0x0;
    const uint64_t ptr5 = 0xAAA0000000000000ULL | 0x5;
    const uint64_t ptr9 = 0xAAA0000000000000ULL | 0x9;

    /* Enable rows on chosen CPU, ensure not suspended */
    prog_row(0, cpu, ptr0, true);
    prog_row(5, cpu, ptr5, true);
    prog_row(9, cpu, ptr9, true);
    set_rx_state(0, /*avail*/false, /*susp*/false);
    set_rx_state(5, false, false);
    set_rx_state(9, false, false);
    set_txc_state(0, false, false);
    set_txc_state(5, false, false);
    set_txc_state(9, false, false);

    /* Read via per-CPU RR port */
    uintptr_t rd = IOCACHE_SCHED_READ(cpu);
    uint64_t expect[6] = { ptr0, ptr5, ptr9, ptr0, ptr5, ptr9 };
    for (int i = 0; i < 6; i++) {
        uint64_t got = do_read(rd, 64);
        printf("  CPU %u: read[%d] = 0x%016" PRIx64 "\n", cpu, i, got);
        if (got != expect[i]) {
            fprintf(stderr, "ERROR: RR mismatch at i=%d (got 0x%016" PRIx64 ", expect 0x%016" PRIx64 ")\n",
                    i, got, expect[i]);
            return -1;
        }
    }

    /* Disable row5 and re-check sequence */
    // do_write(IOCACHE_ENABLED(5), 1, 0);
    set_rx_state(5, false, true);
    uint64_t expect2[4] = { ptr0, ptr9, ptr0, ptr9 };
    for (int i = 0; i < 4; i++) {
        uint64_t got = do_read(rd, 64);
        printf("  CPU %u after disable row5, read[%d] = 0x%016" PRIx64 "\n", cpu, i, got);
        if (got != expect2[i]) {
            fprintf(stderr, "ERROR: RR mismatch (after disable) at i=%d (got 0x%016" PRIx64 ", expect 0x%016" PRIx64 ")\n",
                    i, got, expect2[i]);
            return -2;
        }
    }

    /* Disable row0; only row9 remains */
    // do_write(IOCACHE_ENABLED(0), 1, 0);
    set_rx_state(0, false, true);
    for (int i = 0; i < 4; i++) {
        uint64_t got = do_read(rd, 64);
        printf("  CPU %u after disable row0, read[%d] = 0x%016" PRIx64 "\n", cpu, i, got);
        if (got != ptr9) {
            fprintf(stderr, "ERROR: expected only ptr9, got 0x%016" PRIx64 "\n", got);
            return -3;
        }
    }

    /* No-match case: point the last row to a different CPU */
    // do_write(IOCACHE_ENABLED(9), 1, 0);        /* disable it */
    set_rx_state(9, false, true);
    /* Now nothing enabled for this CPU; reads should return 0 */
    for (int i = 0; i < 2; i++) {
        uint64_t got = do_read(rd, 64);
        printf("  CPU %u (no rows), read[%d] = 0x%016" PRIx64 "\n", cpu, i, got);
        if (got != 0) {
            fprintf(stderr, "ERROR: expected 0 when no eligible rows (got 0x%016" PRIx64 ")\n", got);
            return -4;
        }
    }

    puts("Per-CPU RR test PASSED.");
    return 0;
}

/* ==== Test 2: RX kick-all ==== */
static int test_rx_kick_all(uint32_t cpu)
{
    puts("\n== RX kick-all test ==");

    /* Program three rows owned by 'cpu' */
    const unsigned rows[] = {2, 7, 11};
    const size_t n = sizeof(rows)/sizeof(rows[0]);

    for (size_t i = 0; i < n; i++) {
        unsigned r = rows[i];
        prog_row(r, cpu, 0xBBB0000000000000ULL | r, true);
        set_rx_state(r, /*avail*/1, /*susp*/1);  // eligible for RX IRQ
        set_txc_state(r, 0, 0);
    }

    /* Issue kick-all for this CPU */
    do_write(IOCACHE_RX_KICK_ALL_CPU, 32, cpu);

    /* Read back count and mask */
    uint64_t count = do_read(IOCACHE_RX_KICK_ALL_COUNT, 32);  // width is small, 32 ok
    uint64_t mask  = do_read(IOCACHE_RX_KICK_ALL_MASK,  64);  // packed LSB=row0

    printf("  kick-all: count=%" PRIu64 "  mask=0x%016" PRIx64 "\n", count, mask);

    if (count != n) {
        fprintf(stderr, "ERROR: kick-all count mismatch (got %" PRIu64 ", expect %zu)\n", count, n);
        return -1;
    }

    /* Verify each row's RX_SUSPENDED was cleared */
    for (size_t i = 0; i < n; i++) {
        unsigned r = rows[i];
        uint64_t s = do_read(IOCACHE_RX_SUSPENDED(r), 1);
        if (s != 0) {
            fprintf(stderr, "ERROR: row %u RX_SUSPENDED not cleared (got %" PRIu64 ")\n", r, s);
            return -2;
        }
        /* Mask bit should be set for this row */
        if (((mask >> r) & 1ULL) == 0ULL) {
            fprintf(stderr, "ERROR: mask bit for row %u not set\n", r);
            return -3;
        }
    }

    /* Verify a non-touched row remains suspended or unaffected */
    unsigned other = 3;
    set_rx_state(other, 1, 1);
    do_write(IOCACHE_RX_KICK_ALL_CPU, 32, cpu);  // only clears rows owned by 'cpu'
    uint64_t s_other = do_read(IOCACHE_RX_SUSPENDED(other), 1);
    if (s_other != 1) {
        fprintf(stderr, "ERROR: row %u should remain suspended (owned by ?) got %" PRIu64 "\n", other, s_other);
        return -4;
    }

    puts("RX kick-all test PASSED.");
    return 0;
}

/* ---- Generic reg R/W test (unchanged) ---- */
int run_test(uintptr_t addr, unsigned size_bits, uint64_t val1, uint64_t val2) {
    if (!(size_bits == 1 || size_bits == 8 || size_bits == 16 || size_bits == 32 || size_bits == 64)) {
        fprintf(stderr, "run_test: invalid size=%u (must be 1/8/16/32/64)\n", size_bits);
        return -1;
    }
    const uint64_t m = mask_bits(size_bits);
    const uint64_t vals[2] = { val1 & m, val2 & m };

    printf("Running test for addr=0x%016" PRIxPTR " (size=%u bits)\n", (uintptr_t)addr, size_bits);
    for (size_t i = 0; i < 2; i++) {
        const uint64_t expect = vals[i];
        const uint64_t orig = do_read(addr, size_bits) & m;
        do_write(addr, size_bits, expect);
        const uint64_t got  = do_read(addr, size_bits) & m;

        printf("  orig=0x%016" PRIx64 "  write=0x%016" PRIx64 "  read=0x%016" PRIx64 "  mask=0x%016" PRIx64 "\n",
               orig, expect, got, m);

        if (got != expect) {
            fprintf(stderr, "ERROR: mismatch (expected 0x%016" PRIx64 ", got 0x%016" PRIx64 ")\n",
                    expect, got);
            return -2;
        }
    }
    printf("Test PASSED.\n");
    return 0;
}

int main(void)
{
    uint64_t val1 = 0xffffffffffffffffULL;
    uint64_t val2 = 0x000000000000000fULL;

    /* Simple R/W spot checks on table fields */
    // run_test(IOCACHE_SRC_IP(1),         32, val1, val2);
    // run_test(IOCACHE_RX_AVAILABLE(2),    1, val1, val2);
    // printf("Repeat...\n");
    // run_test(IOCACHE_SRC_IP(1),         32, val1, val2);
    // run_test(IOCACHE_RX_AVAILABLE(2),    1, val1, val2);
    // run_test(IOCACHE_PROTOCOL(0),        8, val1, val2);

    /* Per-CPU scheduler test: pick a CPU index in-range (e.g., 3) */
    if (test_fair_sched_round_robin_per_cpu(/*cpu=*/3) != 0) return 1;

    /* Kick-all test on another CPU (or same) */
    if (test_rx_kick_all(/*cpu=*/2) != 0) return 2;

    printf("All tests PASSED.\n");
    return 0;
}
