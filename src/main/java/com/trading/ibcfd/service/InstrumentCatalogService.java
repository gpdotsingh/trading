package com.trading.ibcfd.service;

import com.trading.ibcfd.config.SaxoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog operations: keyword search and full browse of Saxo instruments.
 * Extracted from InstrumentService to keep each class under 200 lines.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentCatalogService {

    private final SaxoConfig   config;
    private final RestTemplate restTemplate;

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String keyword, String assetType, int top) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/ref/v1/instruments/")
                .queryParam("AssetTypes", assetType)
                .queryParam("Keywords",   keyword)
                .queryParam("$top",       top)
                .build().toUriString();

        log.info("Instrument search: keyword={} assetType={}", keyword, assetType);
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        if (response == null) return List.of();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("Data");
        if (data == null) return List.of();

        return data.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uic",         item.get("Identifier"));
            row.put("symbol",      item.get("Symbol"));
            row.put("description", item.get("Description"));
            row.put("assetType",   item.get("AssetType"));
            row.put("currency",    item.get("CurrencyCode"));
            return (Map<String, Object>) row;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> browse(Integer skip, Integer top, String assetTypes,
            String keywords, String uics, String exchangeId,
            Boolean includeNonTradable, String tags, String clazz,
            Boolean canParticipateInMultiLegOrder) {

        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/ref/v1/instruments");
        b.queryParam("AccountKey", config.getAccountKey());
        b.queryParam("$top", top != null ? top : 20);
        if (skip != null && skip > 0)                    b.queryParam("$skip",                        skip);
        if (assetTypes != null && !assetTypes.isBlank()) b.queryParam("AssetTypes",                   assetTypes);
        if (keywords   != null && !keywords.isBlank())   b.queryParam("Keywords",                     keywords);
        if (uics       != null && !uics.isBlank())        b.queryParam("Uics",                         uics);
        if (exchangeId != null && !exchangeId.isBlank()) b.queryParam("ExchangeId",                   exchangeId);
        if (includeNonTradable != null)                  b.queryParam("IncludeNonTradable",            includeNonTradable);
        if (tags  != null && !tags.isBlank())            b.queryParam("Tags",                         tags);
        if (clazz != null && !clazz.isBlank())           b.queryParam("Class",                        clazz);
        if (canParticipateInMultiLegOrder != null)        b.queryParam("CanParticipateInMultiLegOrder", canParticipateInMultiLegOrder);

        String url = b.build().toUriString();
        log.info("Instrument browse: {}", url);
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        return response != null ? response : Map.of("Data", List.of());
    }
}
