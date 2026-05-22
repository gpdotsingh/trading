package com.trading.ibcfd.controller;

import com.trading.ibcfd.model.OpenPosition;
import com.trading.ibcfd.service.DynamicStopLossService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionMonitorController {

    private final DynamicStopLossService stopLossService;

    @GetMapping
    public List<OpenPosition> getOpen() {
        return stopLossService.getOpenPositions();
    }

    @GetMapping("/all")
    public List<OpenPosition> getAll() {
        return stopLossService.getAllPositions();
    }
}
