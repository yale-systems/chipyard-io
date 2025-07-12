#define ACCNIC_BASE 0x10018000L
#define CTRL_OFFSET 0x00
#define RX_OFFSET 0x1000
#define TX_OFFSET 0x2000

#define RX_BASE (ACCNIC_BASE + RX_OFFSET)
#define TX_BASE (ACCNIC_BASE + TX_OFFSET)
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