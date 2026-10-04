package dev.hoori.micro.recipes.controller;

import dev.hoori.micro.contracts.dto.*;
import dev.hoori.micro.recipes.service.RecipeService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;

@RestController
@RequestMapping("/recipes")
public final class RecipeController {
    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @GetMapping("/{id}")
    @GatewayRoute(permission = "recipes:read")
    public Recipe get(@PathVariable("id") @Positive long id) {
        return recipes.get(id);
    }

    @GetMapping
    @GatewayRoute(permission = "recipes:read")
    public List<Recipe> list(@RequestParam(value = "prefix", defaultValue = "") String prefix) {
        return recipes.list(prefix);
    }

    @PostMapping
    @GatewayRoute(permission = "recipes:write")
    public HttpResult<Recipe> create(@RequestBody @Valid CreateRecipe input) {
        Recipe result = recipes.create(input);

        return HttpResult.of(201, result).header("Location", "/recipes/" + result.id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(204)
    @GatewayRoute(permission = "recipes:write")
    public void delete(@PathVariable("id") @Positive long id) {
        recipes.delete(id);
    }

    @PostMapping("/bulk")
    public List<Recipe> bulk(@RequestBody @Valid RecipeIds input) {
        return recipes.bulk(input);
    }

    @GetMapping("/demo/slow/{id}")
    public Recipe slow(@PathVariable("id") @Positive long id) throws Exception {
        return recipes.slow(id);
    }

    @GetMapping("/demo/context")
    public RequestInfo context() {
        return recipes.context();
    }
}
