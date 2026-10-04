package dev.hoori.micro.data.controller;

import dev.hoori.micro.data.dto.*;
import dev.hoori.micro.data.service.DraftService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.Valid;

@RestController
public final class DraftController {
    private final DraftService drafts;

    public DraftController(DraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/drafts")
    @GatewayRoute(permission = "drafts:write")
    public SavedDraft save(@RequestBody @Valid Draft input) throws Exception {
        return drafts.save(input);
    }
}
