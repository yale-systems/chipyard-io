#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>

#include <riscv-pk/encoding.h>
#include "accnic.h"
#include "iocache_regs.h"
#include "mmio.h"


#define DMA_ALIGN(x) (((x) + 63) & ~63)

#define NPACKETS 5
#define TEST_OFFSET 0
#define TEST_LEN 90
#define ARRAY_LEN DMA_ALIGN(TEST_LEN + NPACKETS + TEST_OFFSET)
#define NTRIALS 3

#define UDP_TEST_LEN 2048
#define UDP_RING_SIZE (8*1024)
#define UDP_ARRAY_LEN DMA_ALIGN(UDP_RING_SIZE)

uint8_t src[NPACKETS][ARRAY_LEN] __attribute__((aligned(64)));
uint8_t dst[NPACKETS][ARRAY_LEN] __attribute__((aligned(64)));

uint8_t udp_src[UDP_ARRAY_LEN] __attribute__((aligned(64)));
uint8_t udp_dst[UDP_ARRAY_LEN] __attribute__((aligned(64)));

uint64_t lengths[NPACKETS];

static inline void send_recv()
{
	uint64_t send_packet;
	int ncomps;

	for (int i = 0; i < NPACKETS; i++) {
		int length = TEST_LEN + i;
		uint64_t pkt_size = length & 0xffff;
		uint64_t src_addr = (uint64_t) &src[i][TEST_OFFSET];
		send_packet = (pkt_size << 48) | src_addr;
		reg_write64(TX_REQ, send_packet);
	}

	printf("Waiting for TX completions...\n");
	while (true) {
		ncomps = reg_read16(TX_COUNT) & 0x3f;
		asm volatile ("fence");

		printf("TX completions available: %d\n", ncomps);

		if (ncomps >= NPACKETS) {
			for (int i = 0; i < NPACKETS; i++) {
				reg_read8(TX_COMP_READ);
			}
			break;
		}
	}

	printf("Waiting for RX completions...\n");
	while (true) {
		ncomps = reg_read16(RX_COMP_COUNT) & 0x3f;
		asm volatile ("fence");

		printf("RX completions available: %d\n", ncomps);

		if (ncomps >= NPACKETS) {
			break;
		}
	}

	printf("Received %d completions. Checking...\n", ncomps);
	for (int i = 0; i < NPACKETS; i++) {
		uint64_t comp_log = reg_read64(RX_COMP_LOG);
		asm volatile ("fence");

		uint32_t pkt_size = comp_log & 0xffff;
		uint64_t addr = (comp_log >> 16) & 0xffffffffffffULL;
		printf("*** Packet %d: size=%u, addr=0x%lx, \ndata=0x", i, pkt_size, addr);
		// for (uint32_t j = 0; j < pkt_size; j++) {
		// 	uint8_t *data = (uint8_t *)(uintptr_t)(addr + j);
		// 	printf("%02x", *data);
		// }
		// printf("\n");
		// printf("*** Original Packet %d: \ndata=0x", i);
		// for (uint32_t j = 0; j < pkt_size; j++) {
		// 	printf("%02x", src[i][j + TEST_OFFSET]);
		// }
		printf("\n");

		lengths[i] = pkt_size;
	}
}

void run_test(void)
{
	unsigned long start, end;
	uint64_t i, j, length;

	memset(dst, 0, sizeof(dst));
	asm volatile ("fence");

	start = rdcycle();
	send_recv();
	end = rdcycle();

	printf("send/recv %lu cycles\n", end - start);

	for (i = 0; i < NPACKETS; i++) {
		length = (TEST_LEN + i);

		if (lengths[i] != length) {
			printf("recv got wrong # bytes\n");
			exit(EXIT_FAILURE);
		}

		for (j = 0; j < length; j++) {
			if (dst[i][j] != src[i][j + TEST_OFFSET]) {
				printf("Data mismatch @ %ld, %ld: %x != %x\n",
					i, j, dst[i][j], src[i][j + TEST_OFFSET]);
				exit(EXIT_FAILURE);
			}
		}
	}
}

static inline void udp_send_recv() {
	uint32_t val;

	uint32_t tx_head = reg_read32(UDP_TX_RING_HEAD);
	uint32_t tx_tail = reg_read32(UDP_TX_RING_TAIL);
	uint32_t rx_head = reg_read32(UDP_RX_RING_HEAD);
	uint32_t rx_tail = reg_read32(UDP_RX_RING_TAIL);

	// printf("Current UDP TX ring head: %d\n", tx_head);
	// printf("Current UDP TX ring tail: %d\n", tx_tail);
	// printf("Current UDP RX ring head: %d\n", rx_head);
	// printf("Current UDP RX ring tail: %d\n", rx_tail);

	tx_tail = (tx_tail + UDP_TEST_LEN) % UDP_RING_SIZE;
	reg_write32(UDP_TX_RING_TAIL, tx_tail);

	printf("Updated tx_tail => %u\n", tx_tail);
	printf("waiting...\n");

	val = reg_read32(IOCACHE_TXCOMP_AVAILABLE(0));
	printf("Check IOCache TXC_AVAIL Before: 0x%08x\n", val);
	
	while (tx_tail != tx_head) {
		tx_head = reg_read32(UDP_TX_RING_HEAD);
		printf("** Read UDP TX ring head: %d\n", tx_head);
	}
	printf("TX complete\n");
	val = reg_read32(IOCACHE_TXCOMP_AVAILABLE(0));
	printf("Check IOCache TXC_AVAIL After: 0x%08x\n", val);

	while (rx_tail != (rx_head + UDP_TEST_LEN) % UDP_RING_SIZE) {
		rx_tail = reg_read32(UDP_RX_RING_TAIL);
		printf("** Read UDP RX ring head: %d\n", rx_tail);
	}
	val = reg_read32(IOCACHE_RX_AVAILABLE(0));
	printf("Check IOCache RX_AVAIL Before: 0x%08x\n", val);

	rx_head = rx_tail;
	reg_write32(UDP_RX_RING_HEAD, rx_head);
	printf("RX complete\n");

	val = reg_read32(IOCACHE_RX_AVAILABLE(0));
	printf("Check IOCache RX_AVAIL After: 0x%08x\n", val);


	// printf("Source=\n");
	// for (uint32_t j = 0; j < UDP_TEST_LEN; j++) {
	// 	printf("%02x", udp_src[j]);
	// }
	// printf("\n");
	
	// printf("Destination=\n");
	// for (uint32_t j = 0; j < UDP_TEST_LEN; j++) {
	// 	printf("%02x", udp_dst[j]);
	// }
	// printf("\n");
}

void run_udp_test(void) {
	unsigned long start, end;
	uint64_t i, j, mod_j;

	memset(udp_dst, 0, sizeof(udp_dst));
	asm volatile ("fence");

	for (i = 0; i < NPACKETS; i++) {
		start = rdcycle();
		udp_send_recv();
		end = rdcycle();
		printf("send/recv %lu cycles\n", end - start);

		for (j = i * UDP_TEST_LEN; j < (i+1) * UDP_TEST_LEN; j++) {
			mod_j = j % UDP_RING_SIZE;
			if (udp_dst[mod_j] != udp_src[mod_j]) {
				printf("UDP Data mismatch @ %ld: %x != %x\n", j, udp_dst[mod_j], udp_src[mod_j]);
				exit(EXIT_FAILURE);
			}
		}
	}
}

void init_rx(void) {
    // Initialize the receive buffer
	printf("Initializing RX engine\n");
    for (int i = 0; i < NPACKETS; i++) {
        uint64_t recv_addr = (uint64_t) dst[i];
        reg_write64(RX_DMA_ADDR, recv_addr);
    }

	printf("setting interrupt mask\n");
	// reg_write8(CTRL_INTR_MASK, 0x3); // Enable interrupts
	reg_write8(CTRL_INTR_MASK, 0x0); // Disable interrupts

	asm volatile ("fence");
}

void init_udp(void) {
	// Contor registers
	reg_write32(CTRL_FILTER_PORT, 1234);
	reg_write32(CTRL_FILTER_IP,   0xA000001); // 10.0.0.1
	// RX
	reg_write32(UDP_RX_RING_SIZE, 0); 		// Make 0 to stop nic from working
	reg_write64(UDP_RX_RING_BASE, (uint64_t) udp_dst);
	reg_write32(UDP_RX_RING_HEAD, 0);
	reg_write32(UDP_RX_RING_TAIL, 0);
	reg_write32(UDP_RX_RING_SIZE, UDP_RING_SIZE);
	// TX
	reg_write32(UDP_TX_RING_SIZE, 0);  		// Make 0 to stop nic from working
	reg_write64(UDP_TX_RING_BASE, (uint64_t) udp_src);
	reg_write32(UDP_TX_RING_HEAD, 0);
	reg_write32(UDP_TX_RING_TAIL, 0);
	reg_write32(UDP_TX_RING_SIZE, UDP_RING_SIZE);
	reg_write16(UDP_TX_MTU, 1472);
	reg_write64(UDP_TX_HDR_MAC_SRC, 0x112233445566);
	reg_write64(UDP_TX_HDR_MAC_DST, 0x887766554433);
	reg_write32(UDP_TX_HDR_IP_SRC, 0x0a0b0c0d);
	reg_write32(UDP_TX_HDR_IP_DST, 0xA000001);
	reg_write8(UDP_TX_HDR_IP_TOS, 0);
	reg_write8(UDP_TX_HDR_IP_TTL, 64);
	reg_write16(UDP_TX_HDR_IP_ID, 0);
	reg_write16(UDP_TX_HDR_UDP_SRC_PORT, 1111);
	reg_write16(UDP_TX_HDR_UDP_DST_PORT, 1234);
	// reg_write8(UDP_TX_HDR_UDP_CSUM, 1500);

	asm volatile ("fence");
}

void init_buffers(void) {
	int i, j;

	for (i = 0; i < NPACKETS; i++) {
		for (j = 0; j < TEST_LEN + i; j++)
			src[i][j] = (i * TEST_LEN + j) & 0xff;
	}

	for (j = 0; j < UDP_ARRAY_LEN; j++) {
		udp_src[j] = j & 0xff;
	}
}

int main(void)
{
	int i;

	init_buffers();

	printf("First IOCACHE offset: %08lx\n", IOCACHE_ENABLED(0));
	printf("Last IOCACHE offset:  %08lx\n", IOCACHE_FLAGS_RO(7));

	// Test Normal Operation
	for (i = 0; i < NTRIALS; i++) {
		printf("Trial %d (Normal Op)\n", i);
		// init_rx();
		// run_test();
	}

	// Test UDP Offload
	for (i = 0; i < NTRIALS; i++) {
		printf("Trial %d (UDP)\n", i);
		init_udp();
		run_udp_test();
	}
	

	printf("All correct\n");

	return 0;
}
