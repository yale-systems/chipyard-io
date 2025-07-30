#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>

#include <riscv-pk/encoding.h>
#include "accnic.h"
#include "mmio.h"


#define DMA_ALIGN(x) (((x) + 63) & ~63)

#define NPACKETS 10
#define TEST_OFFSET 0
#define TEST_LEN 90
#define ARRAY_LEN DMA_ALIGN(TEST_LEN + NPACKETS + TEST_OFFSET)
#define NTRIALS 3

uint8_t src[NPACKETS][ARRAY_LEN] __attribute__((aligned(64)));
uint8_t dst[NPACKETS][ARRAY_LEN] __attribute__((aligned(64)));

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

int main(void)
{
	int i, j;

	for (i = 0; i < NPACKETS; i++) {
		for (j = 0; j < TEST_LEN + i; j++)
			src[i][j] = (i * TEST_LEN + j) & 0xff;
	}

	
	for (i = 0; i < NTRIALS; i++) {
		printf("Trial %d\n", i);
		init_rx();
		run_test();
	}

	printf("All correct\n");

	return 0;
}
