package dev.hoori.micro.shopping.controller;

import dev.hoori.micro.contracts.dto.*;
import dev.hoori.micro.shopping.service.ShoppingService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;

@RestController
public final class ShoppingController {
    private final ShoppingService shopping;

    public ShoppingController(ShoppingService shopping) {
        this.shopping = shopping;
    }

    @GetMapping("/meals/{id}")
    @GatewayRoute(permission = "shopping:read")
    public Recipe meal(@PathVariable("id") @Positive long id) {
        return shopping.meal(id);
    }

    @GetMapping("/meals")
    @GatewayRoute(permission = "shopping:read")
    public List<Recipe> list(@RequestParam(value = "prefix", defaultValue = "") String prefix) {
        return shopping.list(prefix);
    }

    @PostMapping("/meals")
    @GatewayRoute(permission = "shopping:write")
    public HttpResult<Recipe> create(@RequestBody @Valid CreateRecipe input) {
        return shopping.create(input);
    }

    @DeleteMapping("/meals/{id}")
    @GatewayRoute(permission = "shopping:write")
    public void delete(@PathVariable("id") @Positive long id) {
        shopping.delete(id);
    }

    @GetMapping("/overview/{id}")
    @GatewayRoute(permission = "shopping:read")
    public Overview overview(@PathVariable("id") @Positive long id) throws Exception {
        return shopping.overview(id);
    }

    @GetMapping("/dashboard/{id}")
    @GatewayRoute(permission = "shopping:read")
    public Dashboard dashboard(@PathVariable("id") @Positive long id) throws Exception {
        return shopping.dashboard(id);
    }

    @PostMapping("/meals/batch")
    @GatewayRoute(permission = "shopping:read")
    public List<Recipe> batch(@RequestBody @Valid RecipeIds input) throws Exception {
        return shopping.batch(input);
    }

    @PostMapping("/meals/bulk")
    @GatewayRoute(permission = "shopping:read")
    public List<Recipe> bulk(@RequestBody @Valid RecipeIds input) {
        return shopping.bulk(input);
    }

    @GetMapping("/demo/slow/{id}")
    @GatewayRoute(permission = "shopping:demo")
    public Recipe slow(@PathVariable("id") @Positive long id) {
        return shopping.slow(id);
    }

    @GetMapping("/demo/context")
    @GatewayRoute(permission = "shopping:demo")
    public RequestInfo context() {
        return shopping.context();
    }
}
