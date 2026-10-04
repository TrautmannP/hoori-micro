package dev.hoori.micro.crud.controller;

import dev.hoori.micro.crud.dto.CreateRecipe;
import dev.hoori.micro.crud.dto.Recipe;
import dev.hoori.micro.crud.service.RecipeService;
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
    public Recipe get(@PathVariable("id") @Positive long id) {
        return recipes.get(id);
    }

    @GetMapping
    public List<Recipe> list(@RequestParam(value = "prefix", defaultValue = "") String prefix) {
        return recipes.list(prefix);
    }

    @PostMapping
    public HttpResult<Recipe> create(@RequestBody @Valid CreateRecipe input) {
        Recipe recipe = recipes.create(input);

        return HttpResult.of(201, recipe).header("Location", "/recipes/" + recipe.id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(204)
    public void delete(@PathVariable("id") @Positive long id) {
        recipes.delete(id);
    }
}
