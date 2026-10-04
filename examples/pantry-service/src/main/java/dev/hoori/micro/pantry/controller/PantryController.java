package dev.hoori.micro.pantry.controller;

import dev.hoori.micro.pantry.service.PantryService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.constraints.Positive;
import java.util.List;

@RestController
@RequestMapping("/pantry")
public final class PantryController {
    private final PantryService pantry;

    public PantryController(PantryService pantry) {
        this.pantry = pantry;
    }

    @GetMapping("/{id}")
    @GatewayRoute(permission = "pantry:read")
    public List<String> items(@PathVariable("id") @Positive long id) {
        return pantry.items(id);
    }
}
