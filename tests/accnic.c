#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>

#include <riscv-pk/encoding.h>
#include "accnic.h"
#include "mmio.h"

#define NPACKETS 10
#define TEST_OFFSET 3
#define TEST_LEN 356
#define ARRAY_LEN 360
#define NTRIALS 3

uint32_t src[NPACKETS][ARRAY_LEN];
uint32_t dst[NPACKETS][ARRAY_LEN];
uint64_t lengths[NPACKETS];

static inline void send_recv()
{
	uint64_t send_packet;
	int ncomps;

	for (int i = 0; i < NPACKETS; i++) {
		uint64_t pkt_size = (TEST_LEN * sizeof(uint32_t)) & 0xffff;
		uint64_t src_addr = (uint64_t) &src[i][TEST_OFFSET];
		send_packet = (pkt_size << 48) | src_addr;
		reg_write64(TX_REQ, send_packet);
	}

	// printf("Waiting for TX completions...\n");
	while (true) {
		ncomps = reg_read16(TX_COUNT) & 0x3f;
		asm volatile ("fence");

		// printf("TX completions available: %d\n", ncomps);

		if (ncomps >= NPACKETS) {
			break;
		}
	}

	// printf("Waiting for RX completions...\n");
	while (true) {
		ncomps = reg_read16(RX_COMP_COUNT) & 0x3f;
		asm volatile ("fence");

		// printf("RX completions available: %d\n", ncomps);

		if (ncomps >= NPACKETS) {
			break;
		}
	}

	// printf("Received %d completions. Checking...\n", ncomps);
	for (int i = 0; i < NPACKETS; i++) {
		uint64_t comp_log = reg_read64(RX_COMP_LOG);
		asm volatile ("fence");

		uint32_t pkt_size = comp_log & 0xffff;
		uint64_t src_addr = (comp_log >> 16) & 0xffffffffffffULL;

		lengths[i] = pkt_size;
		
		uint32_t *recv_data = (uint32_t *) (src_addr);
		// printf("Packet %d: size=%u, src_addr=%lx\n", *recv_data, pkt_size, src_addr);
	}
}

void run_test(void)
{
	unsigned long start, end;
	int i, j;

	memset(dst, 0, sizeof(dst));
	asm volatile ("fence");

	start = rdcycle();
	send_recv();
	end = rdcycle();

	printf("send/recv %lu cycles\n", end - start);

	for (i = 0; i < NPACKETS; i++) {
		if (lengths[i] != TEST_LEN * sizeof(uint32_t)) {
			printf("recv got wrong # bytes\n");
			exit(EXIT_FAILURE);
		}

		for (j = 0; j < TEST_LEN; j++) {
			if (dst[i][j] != src[i][j + TEST_OFFSET]) {
				printf("Data mismatch @ %d, %d: %x != %x\n",
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
		for (j = 0; j < ARRAY_LEN; j++)
			src[i][j] = i * ARRAY_LEN + j;
	}

	
	for (i = 0; i < NTRIALS; i++) {
		printf("Trial %d\n", i);
		init_rx();
		run_test();
	}

	printf("All correct\n");

	return 0;
}
