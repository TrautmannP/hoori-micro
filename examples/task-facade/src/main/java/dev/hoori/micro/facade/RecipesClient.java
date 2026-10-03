package dev.hoori.micro.facade;

import dev.hoori.micro.demo.GetRecipe;
import dev.hoori.micro.demo.Recipe;
import hoori.tasks.GenerateTasks;
import java.io.IOException;

@GenerateTasks
public interface RecipesClient {
    Recipe get(GetRecipe query) throws IOException;
}
