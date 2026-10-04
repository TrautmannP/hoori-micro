package dev.hoori.micro.crud.repository;

import dev.hoori.micro.crud.dto.Recipe;
import dev.hoori.micro.crud.error.RecipeNotFound;
import hoori.micro.app.Repository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Demo in-memory data; no persistence or production authorization. */
@Repository
public final class RecipeRepository {
    private final LinkedHashMap<Long, Recipe> recipes = new LinkedHashMap<>();
    private long nextId = 1;

    public RecipeRepository() {
        create("Kartoffelsuppe");
    }

    public synchronized Recipe get(long id) {
        Recipe recipe = recipes.get(id);

        if (recipe == null) throw new RecipeNotFound();

        return recipe;
    }

    public synchronized Recipe create(String title) {
        Recipe recipe = new Recipe(nextId++, title);
        recipes.put(recipe.id(), recipe);

        return recipe;
    }

    public synchronized List<Recipe> list(String prefix) {
        List<Recipe> found = new ArrayList<>();
        for (Recipe recipe : recipes.values()) if (recipe.title().startsWith(prefix)) found.add(recipe);

        return found;
    }

    public synchronized void delete(long id) {
        get(id);
        recipes.remove(id);
    }
}
