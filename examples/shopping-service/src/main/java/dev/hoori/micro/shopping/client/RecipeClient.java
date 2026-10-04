package dev.hoori.micro.shopping.client;

import dev.hoori.micro.contracts.dto.*;
import hoori.micro.app.ServiceClient;
import hoori.rest.mvc.*;
import java.util.List;

@ServiceClient(name = "recipes", version = 1)
public interface RecipeClient {
    @GetMapping("/recipes/{id}")
    Recipe get(@PathVariable("id") long id);

    @GetMapping("/recipes")
    List<Recipe> list(@RequestParam(value = "prefix", required = false) String prefix);

    @PostMapping("/recipes")
    HttpResult<Recipe> create(@RequestBody CreateRecipe input);

    @DeleteMapping("/recipes/{id}")
    void delete(@PathVariable("id") long id);

    @PostMapping("/recipes/bulk")
    List<Recipe> bulk(@RequestBody RecipeIds input);

    @GetMapping("/recipes/demo/slow/{id}")
    Recipe slow(@PathVariable("id") long id);

    @GetMapping("/recipes/demo/context")
    RequestInfo context();
}
