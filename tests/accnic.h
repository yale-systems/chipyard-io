#ifndef __ACCNIC_H__
#define __ACCNIC_H__

#include <stdint.h>

/* ---- Top-level bases (unchanged) ---- */
#define ACCNIC_BASE     0x10040000UL
#define CTRL_OFFSET     0x00
#define RX_OFFSET       0x1000
#define TX_OFFSET       0x2000
#define UDP_RX_OFFSET   0x3000
#define UDP_TX_OFFSET   0x4000

#define RX_BASE     (ACCNIC_BASE + RX_OFFSET)
#define TX_BASE     (ACCNIC_BASE + TX_OFFSET)
#define UDP_RX_BASE (ACCNIC_BASE + UDP_RX_OFFSET)
#define UDP_TX_BASE (ACCNIC_BASE + UDP_TX_OFFSET)
#define CTRL_BASE   (ACCNIC_BASE + CTRL_OFFSET)

/* ---- Control (unchanged) ---- */
#define CTRL_INTR_MASK   (CTRL_BASE + 0x00)

/* ===================================================================== */
/* =========================  UDP RX ENGINE  =========================== */
/* ===================================================================== */
#define UDP_RX_RING_STRIDE          0x10UL

#define UDP_RX_RING_HEAD_OFF        0x00UL
#define UDP_RX_RING_TAIL_OFF        0x04UL
#define UDP_RX_RING_DROP_OFF        0x08UL

#define UDP_RX_RING_REG(r, off)    (UDP_RX_BASE + (uint64_t)(r) * UDP_RX_RING_STRIDE + (off))

/* Per-ring convenience */
#define UDP_RX_RING_HEAD(r)        UDP_RX_RING_REG((r), UDP_RX_RING_HEAD_OFF)   /* 32-bit */
#define UDP_RX_RING_TAIL(r)        UDP_RX_RING_REG((r), UDP_RX_RING_TAIL_OFF)   /* 32-bit */
#define UDP_RX_RING_DROP(r)        UDP_RX_RING_REG((r), UDP_RX_RING_DROP_OFF)   /* 32-bit, RO */

/* Engine-level IRQ */
#define UDP_RX_IRQ_PENDING         (UDP_RX_BASE + 0x400UL)
#define UDP_RX_IRQ_CLEAR           (UDP_RX_BASE + 0x404UL)
#define UDP_RX_LAST_TIMESTAMP      (UDP_RX_BASE + 0x408UL)

/* ===================================================================== */
/* =========================  UDP TX ENGINE  =========================== */
/* ===================================================================== */
#define UDP_TX_RING_STRIDE          0x10UL

#define UDP_TX_RING_HEAD_OFF        0x00UL
#define UDP_TX_RING_TAIL_OFF        0x04UL

#define UDP_TX_RING_REG(r, off)    (UDP_TX_BASE + (uint64_t)(r) * UDP_TX_RING_STRIDE + (off))

/* Per-ring convenience */
#define UDP_TX_RING_HEAD(r)        UDP_TX_RING_REG((r), UDP_TX_RING_HEAD_OFF)   /* 32-bit */
#define UDP_TX_RING_TAIL(r)        UDP_TX_RING_REG((r), UDP_TX_RING_TAIL_OFF)   /* 32-bit */

/* Global/header regs */
#define UDP_TX_MTU                 (UDP_TX_BASE + 0x400UL)  /* 16-bit */
#define UDP_TX_HDR_MAC_DST         (UDP_TX_BASE + 0x408UL)  /* 64-bit; write low 48b used */
#define UDP_TX_HDR_MAC_SRC         (UDP_TX_BASE + 0x410UL)  /* 64-bit; write low 48b used */
#define UDP_TX_HDR_IP_TOS          (UDP_TX_BASE + 0x420UL)  /* 8-bit  */
#define UDP_TX_HDR_IP_TTL          (UDP_TX_BASE + 0x424UL)  /* 8-bit  */
#define UDP_TX_HDR_IP_ID           (UDP_TX_BASE + 0x428UL)  /* 16-bit */
#define UDP_TX_HDR_UDP_CSUM0_OK    (UDP_TX_BASE + 0x42CUL)  /* 1-bit  */

/* TX IRQ */
#define UDP_TX_IRQ_PENDING         (UDP_TX_BASE + 0x430UL)
#define UDP_TX_IRQ_CLEAR           (UDP_TX_BASE + 0x434UL)
#define UDP_TX_LAST_TIMESTAMP      (UDP_TX_BASE + 0x438UL)

/* ======================================== */
/* ======== Legacy non-UDP RX/TX  ========= */
/* ======================================== */

/* RX Engine */
#define RX_DMA_ADDR_COUNT   (RX_BASE + 0x04)
#define RX_DMA_ADDR         (RX_BASE + 0x08)
#define RX_COMP_LOG         (RX_BASE + 0x10)
#define RX_COMP_COUNT       (RX_BASE + 0x18)
#define RX_INTR_PEND        (RX_BASE + 0x20)
#define RX_INTR_CLEAR       (RX_BASE + 0x24)

/* TX Engine */
#define TX_REQ_COUNT        (TX_BASE + 0x00)
#define TX_REQ              (TX_BASE + 0x08)
#define TX_COUNT            (TX_BASE + 0x10)
#define TX_COMP_READ        (TX_BASE + 0x12)
#define TX_INTR_PEND        (TX_BASE + 0x18)
#define TX_INTR_CLEAR       (TX_BASE + 0x1C)

#endif /* __ACCNIC_H__ */
