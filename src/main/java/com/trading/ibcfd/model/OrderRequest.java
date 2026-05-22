package com.trading.ibcfd.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderRequest {

    /** CFD symbol, e.g. "AAPL", "SPX500" */
    @NotBlank(message = "symbol is required")
    private String symbol;

    /** BUY or SELL */
    @NotBlank(message = "action must be BUY or SELL")
    private String action;

    /** Number of contracts/shares */
    @NotNull
    @Positive(message = "quantity must be positive")
    private double quantity;

    /**
     * Saxo asset type. Defaults to CfdOnStock.
     * Options: CfdOnStock, CfdOnIndex, CfdOnEtf, FxSpot
     */
    @Builder.Default
    private String assetType = "CfdOnStock";

    /**
     * Order type: MKT, LMT, STP
     * Default: MKT
     */
    @Builder.Default
    private String orderType = "MKT";

    /** Required when orderType = LMT */
    private double limitPrice;

    /** Required when orderType = STP */
    private double stopPrice;

    /** Absolute stop-loss price — 0 means no stop loss */
    private double stopLossPrice;

    /** Absolute take-profit price — 0 means no take profit */
    private double takeProfitPrice;
}
