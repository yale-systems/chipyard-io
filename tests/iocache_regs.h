#ifndef __IOCACHE_REGS_H__
#define __IOCACHE_REGS_H__

#include <stdint.h>

/* ---- Base addresses ---- */
#define IOCACHE_BASE         0x10017000UL
#define IOCACHE_TABLE_BASE   (IOCACHE_BASE + 0x100UL)   /* table starts at +0x100 */

/* ---- Bus parameters ---- */
#define IOCACHE_BEAT_BYTES   8UL     /* one TileLink beat = 8 bytes = 64 bits */
#define IOCACHE_ROW_STRIDE   0x80UL  /* 128B stride per row (16 * BEAT_BYTES) */

/* ---- Global registers ---- */
#define IOCACHE_REG_INTMASK  (IOCACHE_BASE + 0x00UL)

/* ---- Row field offsets (relative to row base) ---- */
#define IOCACHE_ENABLED_OFF           0x00UL
#define IOCACHE_PROTOCOL_OFF          0x08UL
#define IOCACHE_SRC_IP_OFF            0x10UL
#define IOCACHE_SRC_PORT_OFF          0x18UL
#define IOCACHE_DST_IP_OFF            0x20UL
#define IOCACHE_DST_PORT_OFF          0x28UL
#define IOCACHE_RX_AVAILABLE_OFF      0x30UL
#define IOCACHE_RX_SUSPENDED_OFF      0x38UL
#define IOCACHE_TXCOMP_AVAILABLE_OFF  0x40UL
#define IOCACHE_TXCOMP_SUSPENDED_OFF  0x48UL
#define IOCACHE_FLAGS_RO_OFF          0x50UL

/* ---- Macro to compute register address for row i ---- */
#define IOCACHE_REG(row, off) \
    (IOCACHE_TABLE_BASE + (row) * IOCACHE_ROW_STRIDE + (off))

/* ---- Convenience macros ---- */
#define IOCACHE_ENABLED(row)          IOCACHE_REG((row), IOCACHE_ENABLED_OFF)
#define IOCACHE_PROTOCOL(row)         IOCACHE_REG((row), IOCACHE_PROTOCOL_OFF)
#define IOCACHE_SRC_IP(row)           IOCACHE_REG((row), IOCACHE_SRC_IP_OFF)
#define IOCACHE_SRC_PORT(row)         IOCACHE_REG((row), IOCACHE_SRC_PORT_OFF)
#define IOCACHE_DST_IP(row)           IOCACHE_REG((row), IOCACHE_DST_IP_OFF)
#define IOCACHE_DST_PORT(row)         IOCACHE_REG((row), IOCACHE_DST_PORT_OFF)
#define IOCACHE_RX_AVAILABLE(row)     IOCACHE_REG((row), IOCACHE_RX_AVAILABLE_OFF)
#define IOCACHE_RX_SUSPENDED(row)     IOCACHE_REG((row), IOCACHE_RX_SUSPENDED_OFF)
#define IOCACHE_TXCOMP_AVAILABLE(row) IOCACHE_REG((row), IOCACHE_TXCOMP_AVAILABLE_OFF)
#define IOCACHE_TXCOMP_SUSPENDED(row) IOCACHE_REG((row), IOCACHE_TXCOMP_SUSPENDED_OFF)
#define IOCACHE_FLAGS_RO(row)         IOCACHE_REG((row), IOCACHE_FLAGS_RO_OFF)

#endif /* __IOCACHE_REGS_H__ */
