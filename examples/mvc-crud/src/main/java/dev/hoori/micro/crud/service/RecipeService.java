package dev.hoori.micro.crud.service;

import dev.hoori.micro.crud.dto.CreateRecipe;
import dev.hoori.micro.crud.dto.Recipe;
import dev.hoori.micro.crud.repository.RecipeRepository;
import hoori.micro.app.Service;
import java.util.List;

@Service
public final class RecipeService {
    private final RecipeRepository recipes;

    public RecipeService(RecipeRepository recipes) {
        this.recipes = recipes;
    }

    public Recipe get(long id) {
        return recipes.get(id);
    }

    public List<Recipe> list(String prefix) {
        return recipes.list(prefix);
    }

    public Recipe create(CreateRecipe input) {
        return recipes.create(input.title());
    }

    public void delete(long id) {
        recipes.delete(id);
    }
}
