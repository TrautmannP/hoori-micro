package dev.hoori.micro.facade.client;

import dev.hoori.micro.contracts.dto.Recipe;
import hoori.micro.app.ServiceClient;
import hoori.rest.mvc.GetMapping;
import hoori.rest.mvc.PathVariable;
import hoori.tasks.GenerateTasks;

@GenerateTasks
@ServiceClient(name = "recipes")
public interface RecipeClient {
    @GetMapping("/recipes/{id}")
    Recipe get(@PathVariable("id") long id);
}
