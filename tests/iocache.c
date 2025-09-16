#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>

// #include <riscv-pk/encoding.h>
#include "mmio.h"
#include "iocache_regs.h"


uint64_t do_read(uintptr_t addr, int size) {
    switch (size)
    {
    case 1:
    case 8:
        return reg_read8(addr);
    case 16:
        return reg_read16(addr);
    case 32:
        return reg_read32(addr);
    case 64:
        return reg_read64(addr);
    default:
        return 0;
    }
}

void do_write(uintptr_t addr, int size, uint64_t val) {
    switch (size)
    {
    case 1:
    case 8:
        reg_write8(addr, (uint8_t)val);
        break;
    case 16:
        reg_write16(addr, (uint16_t)val);
        break;
    case 32:
        reg_write32(addr, (uint32_t)val);
        break;
    case 64:
        reg_write64(addr, val);
        break;
    default:
        break;
    }
}


static inline uint64_t mask_bits(unsigned bits) {
    return (bits >= 64) ? ~0ULL : ((1ULL << bits) - 1ULL);
}

/* --- Fair scheduler test --- */

static void prog_row(unsigned row, uint32_t cpu, uint64_t ptr, bool enable)
{
    do_write(IOCACHE_PROC_CPU(row), 32, cpu);
    do_write(IOCACHE_PROC_PTR(row), 64, ptr);   /* write process pointer */
    do_write(IOCACHE_ENABLED(row),   1, enable ? 1 : 0);
}


static int test_fair_sched_round_robin(void)
{
    puts("\n== Fair scheduler test (CPU_SEL=3; rows {0,5,9}) ==");

    prog_row(0, 0, 0, false);
    prog_row(5, 0, 0, false);
    prog_row(9, 0, 0, false);

    const uint32_t cpu = 3;
    const uint64_t ptr0 = 0xAAA0000000000000ULL | 0x0;
    const uint64_t ptr5 = 0xAAA0000000000000ULL | 0x5;
    const uint64_t ptr9 = 0xAAA0000000000000ULL | 0x9;

    prog_row(0, cpu, ptr0, true);
    prog_row(5, cpu, ptr5, true);
    prog_row(9, cpu, ptr9, true);

    do_write(IOCACHE_REG_CPU_SEL, 32, cpu);

    uint64_t expect[6] = { ptr0, ptr5, ptr9, ptr0, ptr5, ptr9 };
    for (int i = 0; i < 6; i++) {
        uint64_t got = do_read(IOCACHE_REG_SCHED_READ, 64);
        printf("  read[%d] = 0x%016" PRIx64 "\n", i, got);
        if (got != expect[i]) {
            fprintf(stderr, "ERROR: RR mismatch at i=%d (got 0x%016" PRIx64 ", expect 0x%016" PRIx64 ")\n",
                    i, got, expect[i]);
            return -1;
        }
    }

    /* Disable row5 and re-check */
    do_write(IOCACHE_ENABLED(5), 1, 0);
    uint64_t expect2[4] = { ptr0, ptr9, ptr0, ptr9 };
    for (int i = 0; i < 4; i++) {
        uint64_t got = do_read(IOCACHE_REG_SCHED_READ, 64);
        printf("  after disable row5, read[%d] = 0x%016" PRIx64 "\n", i, got);
        if (got != expect2[i]) {
            fprintf(stderr, "ERROR: RR mismatch (after disable) at i=%d (got 0x%016" PRIx64 ", expect 0x%016" PRIx64 ")\n",
                    i, got, expect2[i]);
            return -2;
        }
    }

    /* No match case */
    do_write(IOCACHE_REG_CPU_SEL, 32, 99);
    for (int i = 0; i < 2; i++) {
        uint64_t got = do_read(IOCACHE_REG_SCHED_READ, 64);
        printf("  CPU_SEL=99, read[%d] = 0x%016" PRIx64 "\n", i, got);
        if (got != 0) {
            fprintf(stderr, "ERROR: expected 0 when no eligible rows (got 0x%016" PRIx64 ")\n", got);
            return -3;
        }
    }

    puts("Fair scheduler test PASSED.");
    return 0;
}


/* size = width in bits (allowed: 8,16,32,64). do_read/do_write are yours. */
int run_test(uintptr_t addr, unsigned size, uint64_t val1, uint64_t val2) {
    if (!(size == 1 || size == 8 || size == 16 || size == 32 || size == 64)) {
        fprintf(stderr, "run_test: invalid size=%u (must be 8/16/32/64)\n", size);
        return -1;
    }

    const uint64_t m = mask_bits(size);
    const uint64_t vals[2] = { val1 & m, val2 & m };

    printf("Running test for addr=0x%016" PRIxPTR " (size=%u bits)\n", (uintptr_t)addr, size);

    for (size_t i = 0; i < 2; i++) {
        const uint64_t expect = vals[i];

        /* Write test value, read back, verify */
        const uint64_t orig = do_read(addr, size) & m;
        do_write(addr, size, expect);
        const uint64_t got = do_read(addr, size) & m;

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
	
    uint64_t val1 = 0xffffffffffffffff;
    uint64_t val2 = 0x000000000000000f;
	
    run_test(IOCACHE_SRC_IP(1), 32, val1, val2);
    run_test(IOCACHE_RX_AVAILABLE(2), 1, val1, val2);
    printf("Repeat...\n");
    run_test(IOCACHE_SRC_IP(1), 32, val1, val2);
    run_test(IOCACHE_RX_AVAILABLE(2), 1, val1, val2);
    
    run_test(IOCACHE_PROTOCOL(0), 8, val1, val2);

    /* New scheduler test */
    if (test_fair_sched_round_robin() != 0) {
        return 1;
    }
    
	printf("All correct\n");

	return 0;
}