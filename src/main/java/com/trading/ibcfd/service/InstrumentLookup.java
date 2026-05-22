package com.trading.ibcfd.service;

import com.trading.ibcfd.model.InstrumentDetails;

/** Resolves Saxo instrument identifiers (UICs) and fetches instrument metadata. */
public interface InstrumentLookup {
    int findUic(String symbol, String assetType);
    InstrumentDetails getDetails(String symbol, String assetType);
}
