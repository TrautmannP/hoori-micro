package dev.hoori.micro.recipes.repository;

import dev.hoori.micro.contracts.dto.Recipe;
import dev.hoori.micro.recipes.error.RecipeNotFound;
import dev.hoori.micro.recipes.error.RecipeStoreFull;
import hoori.micro.app.Repository;
import java.util.*;

/** Bounded demo memory only; no production persistence or authorization. */
@Repository
public final class RecipeRepository {
    private final LinkedHashMap<Long, Recipe> recipes = new LinkedHashMap<>();
    private long sequence;

    public RecipeRepository() {
        create("Kartoffelsuppe");
        create("Apfelstrudel");
    }

    public synchronized Recipe get(long id) {
        Recipe result = recipes.get(id);

        if (result == null) throw new RecipeNotFound();

        return result;
    }

    public synchronized Recipe create(String title) {
        if (recipes.size() == 128) throw new RecipeStoreFull();

        Recipe recipe = new Recipe(++sequence, title);
        recipes.put(recipe.id(), recipe);

        return recipe;
    }

    public synchronized List<Recipe> list(String prefix) {
        List<Recipe> result = new ArrayList<>();
        for (Recipe recipe : recipes.values()) if (recipe.title().startsWith(prefix)) result.add(recipe);

        return result;
    }

    public synchronized void delete(long id) {
        get(id);
        recipes.remove(id);
    }
}
