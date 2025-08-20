#!/bin/bash
set -xe  # Exit on error

# Step 1: Build the test binary
echo "[INFO] Building accnic.riscv..."
make -C ../../tests/build accnic

# Step 2: Run the Verilator simulation with the built binary
echo "[INFO] Running Verilator simulation with accnic.riscv..."
make -j32 CONFIG=LoopbackAccNICRocketConfig run-binary BINARY=../../tests/accnic.riscv
