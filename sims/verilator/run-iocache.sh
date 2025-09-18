#!/bin/bash
set -xe  # Exit on error

# Step 1: Build the test binary
echo "[INFO] Building iocache.riscv..."
make -C ../../tests/build iocache

# Step 2: Run the Verilator simulation with the built binary
echo "[INFO] Running Verilator simulation with iocache.riscv..."
make -j32 CONFIG=LoopbackQuadAccNICRocketConfig run-binary BINARY=../../tests/iocache.riscv
