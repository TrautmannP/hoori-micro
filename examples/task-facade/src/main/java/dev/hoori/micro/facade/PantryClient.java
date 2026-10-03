package dev.hoori.micro.facade;

import dev.hoori.micro.demo.GetRecipe;
import hoori.tasks.GenerateTasks;
import java.io.IOException;
import java.util.List;

@GenerateTasks
public interface PantryClient {
    List<String> items(GetRecipe query) throws IOException;
}
