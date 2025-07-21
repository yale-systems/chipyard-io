// Language: Verilog 2001

`resetall
`timescale 1ns / 1ps
`default_nettype none

/*
 * Synchronizes switch and button inputs with a slow sampled shift register
 */
module QSFPCore #(
    parameter ASYNC_FIFO_DEPTH = 1 << 12,
    parameter DATA_WIDTH = 64,
    parameter KEEP_WIDTH = DATA_WIDTH / 8
)(
    input  wire         clk,
    input  wire         rst,

    /*
     * Ethernet: QSFP28
     */
    output wire [3:0]   qsfp1_tx_p,
    output wire [3:0]   qsfp1_tx_n,
    input  wire [3:0]   qsfp1_rx_p,
    input  wire [3:0]   qsfp1_rx_n,
    input  wire         qsfp1_refclk_p,
    input  wire         qsfp1_refclk_n,

    output wire         qsfp1_modsell,
    output wire         qsfp1_resetl,
    input  wire         qsfp1_modprsl,
    input  wire         qsfp1_intl,
    output wire         qsfp1_lpmode,

    input wire         clk_125mhz_int,
    input wire         rst_125mhz_int,

    output wire                     rx_valid,
    output wire [DATA_WIDTH-1:0]    rx_data,
    output wire [KEEP_WIDTH-1:0]    rx_keep,
    output wire                     rx_last,
    output wire                     rx_user,
    input wire                      rx_ready,

    input wire                      tx_valid,
    input wire  [DATA_WIDTH-1:0]    tx_data,
    input wire  [KEEP_WIDTH-1:0]    tx_keep,
    input wire                      tx_last,
    input wire                      tx_user,
    output wire                     tx_ready
);

assign qsfp1_modsell = 1'b0;
assign qsfp1_resetl = 1'b1;
assign qsfp1_lpmode = 1'b0;

// QSFP1 CMAC
wire                           qsfp1_tx_clk_int;
wire                           qsfp1_tx_rst_int;

wire [511:0]                   qsfp1_tx_axis_tdata_int;
wire [63:0]                    qsfp1_tx_axis_tkeep_int;
wire                           qsfp1_tx_axis_tvalid_int;
wire                           qsfp1_tx_axis_tready_int;
wire                           qsfp1_tx_axis_tlast_int;
wire [16+1-1:0]                qsfp1_tx_axis_tuser_int;

wire [79:0]                    qsfp1_tx_ptp_time_int;
wire [79:0]                    qsfp1_tx_ptp_ts_int;
wire [15:0]                    qsfp1_tx_ptp_ts_tag_int;
wire                           qsfp1_tx_ptp_ts_valid_int;

wire                           qsfp1_rx_clk_int;
wire                           qsfp1_rx_rst_int;

wire [511:0]                   qsfp1_rx_axis_tdata_int;
wire [63:0]                    qsfp1_rx_axis_tkeep_int;
wire                           qsfp1_rx_axis_tvalid_int;
wire                           qsfp1_rx_axis_tlast_int;
wire [80+1-1:0]                qsfp1_rx_axis_tuser_int;

wire [79:0]                    qsfp1_rx_ptp_time_int;

wire        qsfp1_drp_clk;
wire        qsfp1_drp_rst;
wire [23:0] qsfp1_drp_addr;
wire [15:0] qsfp1_drp_di;
wire        qsfp1_drp_en;
wire        qsfp1_drp_we;
wire [15:0] qsfp1_drp_do;
wire        qsfp1_drp_rdy;

assign qsfp1_drp_clk = clk_125mhz_int;
assign qsfp1_drp_rst = rst_125mhz_int;

wire       qsfp1_tx_enable;
wire       qsfp1_tx_lfc_en;
wire       qsfp1_tx_lfc_req;
wire [7:0] qsfp1_tx_pfc_en;
wire [7:0] qsfp1_tx_pfc_req;

wire       qsfp1_rx_enable;
wire       qsfp1_rx_status;
wire       qsfp1_rx_lfc_en;
wire       qsfp1_rx_lfc_req;
wire       qsfp1_rx_lfc_ack;
wire [7:0] qsfp1_rx_pfc_en;
wire [7:0] qsfp1_rx_pfc_req;
wire [7:0] qsfp1_rx_pfc_ack;

wire qsfp1_gtpowergood;

wire qsfp1_mgt_refclk_0;
wire qsfp1_mgt_refclk_0_int;
wire qsfp1_mgt_refclk_0_bufg;

IBUFDS_GTE4 ibufds_gte4_qsfp1_mgt_refclk_0_inst (
    .I     (qsfp1_refclk_p),
    .IB    (qsfp1_refclk_n),
    .CEB   (1'b0),
    .O     (qsfp1_mgt_refclk_0),
    .ODIV2 (qsfp1_mgt_refclk_0_int)
);

BUFG_GT bufg_gt_qsfp1_mgt_refclk_0_inst (
    .CE      (qsfp1_gtpowergood),
    .CEMASK  (1'b1),
    .CLR     (1'b0),
    .CLRMASK (1'b1),
    .DIV     (3'd0),
    .I       (qsfp1_mgt_refclk_0_int),
    .O       (qsfp1_mgt_refclk_0_bufg)
);

wire qsfp1_rst;

sync_reset #(
    .N(4)
)
qsfp1_sync_reset_inst (
    .clk(qsfp1_mgt_refclk_0_bufg),
    .rst(rst_125mhz_int),
    .out(qsfp1_rst)
);

cmac_gty_wrapper #(
    .DRP_CLK_FREQ_HZ(125000000),
    .AXIS_DATA_WIDTH(512),
    .AXIS_KEEP_WIDTH(64),
    .TX_SERDES_PIPELINE(0),
    .RX_SERDES_PIPELINE(0),
    .RS_FEC_ENABLE(1)
)
qsfp1_cmac_inst (
    .xcvr_ctrl_clk(clk_125mhz_int),
    .xcvr_ctrl_rst(qsfp1_rst),

    /*
     * Common
     */
    .xcvr_gtpowergood_out(qsfp1_gtpowergood),
    .xcvr_ref_clk(qsfp1_mgt_refclk_0),

    /*
     * DRP
     */
    .drp_clk(qsfp1_drp_clk),
    .drp_rst(qsfp1_drp_rst),
    .drp_addr(qsfp1_drp_addr),
    .drp_di(qsfp1_drp_di),
    .drp_en(qsfp1_drp_en),
    .drp_we(qsfp1_drp_we),
    .drp_do(qsfp1_drp_do),
    .drp_rdy(qsfp1_drp_rdy),

    /*
     * Serial data
     */
    .xcvr_txp(qsfp1_tx_p),
    .xcvr_txn(qsfp1_tx_n),
    .xcvr_rxp(qsfp1_rx_p),
    .xcvr_rxn(qsfp1_rx_n),

    /*
     * CMAC connections
     */
    .tx_clk(qsfp1_tx_clk_int),
    .tx_rst(qsfp1_tx_rst_int),

    .tx_axis_tdata(qsfp1_tx_axis_tdata_int),
    .tx_axis_tkeep(qsfp1_tx_axis_tkeep_int),
    .tx_axis_tvalid(qsfp1_tx_axis_tvalid_int),
    .tx_axis_tready(qsfp1_tx_axis_tready_int),
    .tx_axis_tlast(qsfp1_tx_axis_tlast_int),
    .tx_axis_tuser(qsfp1_tx_axis_tuser_int),

    .tx_ptp_time(qsfp1_tx_ptp_time_int),
    .tx_ptp_ts(qsfp1_tx_ptp_ts_int),
    .tx_ptp_ts_tag(qsfp1_tx_ptp_ts_tag_int),
    .tx_ptp_ts_valid(qsfp1_tx_ptp_ts_valid_int),

    .tx_enable(1'b1),
    .tx_lfc_en(0),
    .tx_lfc_req(0),
    .tx_pfc_en(0),
    .tx_pfc_req(0),

    .rx_clk(qsfp1_rx_clk_int),
    .rx_rst(qsfp1_rx_rst_int),

    .rx_axis_tdata(qsfp1_rx_axis_tdata_int),
    .rx_axis_tkeep(qsfp1_rx_axis_tkeep_int),
    .rx_axis_tvalid(qsfp1_rx_axis_tvalid_int),
    .rx_axis_tlast(qsfp1_rx_axis_tlast_int),
    .rx_axis_tuser(qsfp1_rx_axis_tuser_int),

    // .rx_ptp_time(qsfp1_rx_ptp_time_int),

    .rx_status(qsfp1_rx_status),

    .rx_enable(1'b1),
    .rx_lfc_en(0),
    .rx_lfc_req(),
    .rx_lfc_ack(0),
    .rx_pfc_en(0),
    .rx_pfc_req(),
    .rx_pfc_ack(0)
);

wire                      tx_valid_int;
wire  [511:0]             tx_data_int;
wire  [63:0]              tx_keep_int;
wire                      tx_last_int;
wire                      tx_user_int;
wire                      tx_ready_int;

wire                      rx_valid_int;
wire  [511:0]             rx_data_int;
wire  [63:0]              rx_keep_int;
wire                      rx_last_int;
wire                      rx_user_int;
wire                      rx_ready_int;


axis_async_fifo # (
    .DEPTH(ASYNC_FIFO_DEPTH),
    .DATA_WIDTH(512),
    .KEEP_WIDTH(64),
    .LAST_ENABLE(1),
    .ID_ENABLE(0),
    .DEST_ENABLE(0),
    .USER_ENABLE(1),
    .USER_WIDTH(1),
    .OUTPUT_FIFO_ENABLE(0),
    .FRAME_FIFO(1),
    .DROP_WHEN_FULL(1),

    .USER_BAD_FRAME_VALUE(1'b1),
    .USER_BAD_FRAME_MASK(1'b1),
    .DROP_BAD_FRAME(1)
)
axis_rx_fifo (
    .s_clk(qsfp1_rx_clk_int),
    .s_rst(qsfp1_rx_rst_int),
    .s_axis_tdata(qsfp1_rx_axis_tdata_int),
    .s_axis_tkeep(qsfp1_rx_axis_tkeep_int),
    .s_axis_tvalid(qsfp1_rx_axis_tvalid_int),
    .s_axis_tlast(qsfp1_rx_axis_tlast_int),
    .s_axis_tuser(qsfp1_rx_axis_tuser_int),

    .m_clk(clk),
    .m_rst(rst),
    .m_axis_tdata(rx_data_int),
    .m_axis_tready(rx_ready_int),
    .m_axis_tkeep(rx_keep_int),
    .m_axis_tvalid(rx_valid_int),
    .m_axis_tlast(rx_last_int),
    .m_axis_tuser(rx_user_int)
);

axis_async_fifo # (
    .DEPTH(ASYNC_FIFO_DEPTH),
    .DATA_WIDTH(512),
    .KEEP_WIDTH(64),
    .LAST_ENABLE(1),
    .ID_ENABLE(0),
    .DEST_ENABLE(0),
    .USER_ENABLE(1),
    .USER_WIDTH(1),
    .OUTPUT_FIFO_ENABLE(1),
    .FRAME_FIFO(1),
    .DROP_WHEN_FULL(0),

    .USER_BAD_FRAME_VALUE(1'b1),
    .USER_BAD_FRAME_MASK(1'b1),
    .DROP_BAD_FRAME(1)
)
axis_tx_fifo (
    .s_clk(clk),
    .s_rst(rst),
    .s_axis_tdata(tx_data_int),
    .s_axis_tkeep(tx_keep_int),
    .s_axis_tvalid(tx_valid_int),
    .s_axis_tready(tx_ready_int),
    .s_axis_tlast(tx_last_int),
    .s_axis_tuser(tx_user_int),

    .m_clk(qsfp1_tx_clk_int),
    .m_rst(qsfp1_tx_rst_int),
    .m_axis_tdata(qsfp1_tx_axis_tdata_int),
    .m_axis_tkeep(qsfp1_tx_axis_tkeep_int),
    .m_axis_tvalid(qsfp1_tx_axis_tvalid_int),
    .m_axis_tready(qsfp1_tx_axis_tready_int),
    .m_axis_tlast(qsfp1_tx_axis_tlast_int),
    .m_axis_tuser(qsfp1_tx_axis_tuser_int)
);


axis_adapter #(
    .S_DATA_WIDTH(DATA_WIDTH),
    .M_DATA_WIDTH(512)
) 
axis_tx_adapter (
    .clk(clk),
    .rst(rst),

    .s_axis_tdata(tx_data),
    .s_axis_tkeep(tx_keep),
    .s_axis_tvalid(tx_valid),
    .s_axis_tready(tx_ready),
    .s_axis_tlast(tx_last),
    .s_axis_tuser(tx_user),

    .m_axis_tdata(tx_data_int),
    .m_axis_tkeep(tx_keep_int),
    .m_axis_tvalid(tx_valid_int),
    .m_axis_tready(tx_ready_int),
    .m_axis_tlast(tx_last_int),
    .m_axis_tuser(tx_user_int)
);

axis_adapter #(
    .S_DATA_WIDTH(512),
    .M_DATA_WIDTH(DATA_WIDTH)
)
axis_rx_adapter (
    .clk(clk),
    .rst(rst),

    .s_axis_tdata(rx_data_int),
    .s_axis_tkeep(rx_keep_int),
    .s_axis_tvalid(rx_valid_int),
    .s_axis_tready(rx_ready_int),
    .s_axis_tlast(rx_last_int),
    .s_axis_tuser(rx_user_int),

    .m_axis_tdata(rx_data),
    .m_axis_tkeep(rx_keep),
    .m_axis_tvalid(rx_valid),
    .m_axis_tready(rx_ready),
    .m_axis_tlast(rx_last),
    .m_axis_tuser(rx_user)
);



endmodule
`resetall