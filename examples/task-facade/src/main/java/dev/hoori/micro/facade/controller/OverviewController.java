package dev.hoori.micro.facade.controller;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.facade.service.OverviewService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.constraints.Positive;

@RestController
public final class OverviewController {
    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/overview/{id}")
    @GatewayRoute(permission = "facade:read")
    public Overview get(@PathVariable("id") @Positive long id) throws Exception {
        return overview.get(id);
    }
}
