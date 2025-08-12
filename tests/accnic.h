#define ACCNIC_BASE 0x10018000L
#define CTRL_OFFSET 0x00
#define RX_OFFSET 0x1000
#define TX_OFFSET 0x2000
#define UDP_RX_OFFSET 0x3000
#define UDP_TX_OFFSET 0x4000

#define RX_BASE (ACCNIC_BASE + RX_OFFSET)
#define TX_BASE (ACCNIC_BASE + TX_OFFSET)
#define UDP_RX_BASE (ACCNIC_BASE + UDP_RX_OFFSET)
#define UDP_TX_BASE (ACCNIC_BASE + UDP_TX_OFFSET)
#define CTRL_BASE (ACCNIC_BASE + CTRL_OFFSET)

// Control registers
#define CTRL_INTR_MASK (CTRL_BASE + 0x00)

// RX Engine registers
#define RX_DMA_ADDR_COUNT (RX_BASE + 0x04)
#define RX_DMA_ADDR (RX_BASE + 0x08)
#define RX_COMP_LOG (RX_BASE + 0x10)
#define RX_COMP_COUNT (RX_BASE + 0x18)
#define RX_INTR_PEND (RX_BASE + 0x20)
#define RX_INTR_CLEAR (RX_BASE + 0x24)

// TX Engine registers
#define TX_REQ_COUNT (TX_BASE + 0x00)
#define TX_REQ (TX_BASE + 0x08)
#define TX_COUNT (TX_BASE + 0x10)
#define TX_COMP_READ (TX_BASE + 0x12)
#define TX_INTR_PEND (TX_BASE + 0x18)
#define TX_INTR_CLEAR (TX_BASE + 0x1C)

// UDP RX Engine registers
#define UDP_RX_RING_BASE (UDP_RX_BASE + 0x00)
#define UDP_RX_RING_SIZE (UDP_RX_BASE + 0x08)
#define UDP_RX_RING_HEAD (UDP_RX_BASE + 0x0C)
#define UDP_RX_RING_TAIL (UDP_RX_BASE + 0x10)

// UDP TX Engine registers
#define UDP_TX_RING_BASE        (UDP_TX_BASE + 0x00)
#define UDP_TX_RING_SIZE        (UDP_TX_BASE + 0x08)
#define UDP_TX_RING_HEAD        (UDP_TX_BASE + 0x0C)
#define UDP_TX_RING_TAIL        (UDP_TX_BASE + 0x10)
#define UDP_TX_MTU              (UDP_TX_BASE + 0x14)
#define UDP_TX_HDR_MAC_SRC      (UDP_TX_BASE + 0x20)
#define UDP_TX_HDR_MAC_DST      (UDP_TX_BASE + 0x28)
#define UDP_TX_HDR_IP_SRC       (UDP_TX_BASE + 0x30)
#define UDP_TX_HDR_IP_DST       (UDP_TX_BASE + 0x34)
#define UDP_TX_HDR_IP_TOS       (UDP_TX_BASE + 0x38)
#define UDP_TX_HDR_IP_TTL       (UDP_TX_BASE + 0x39)
#define UDP_TX_HDR_IP_ID        (UDP_TX_BASE + 0x3A)
#define UDP_TX_HDR_UDP_SRC_PORT (UDP_TX_BASE + 0x40)
#define UDP_TX_HDR_UDP_DST_PORT (UDP_TX_BASE + 0x42)
#define UDP_TX_HDR_UDP_CSUM     (UDP_TX_BASE + 0x44)