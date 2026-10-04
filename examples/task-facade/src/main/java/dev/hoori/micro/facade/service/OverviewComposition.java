package dev.hoori.micro.facade.service;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.facade.client.PantryClientTasks;
import dev.hoori.micro.facade.client.RecipeClientTasks;
import hoori.concurrent.Tasks;
import hoori.micro.app.Service;

@Service
public final class OverviewComposition implements OverviewService {
    private final RecipeClientTasks recipes;
    private final PantryClientTasks pantry;

    public OverviewComposition(RecipeClientTasks recipes, PantryClientTasks pantry) {
        this.recipes = recipes;
        this.pantry = pantry;
    }

    public Overview get(long id) throws Exception {
        return Tasks.parallel(recipes.get(id), pantry.items(id))
                .named("facade.overview")
                .failFast()
                .map(Overview::new);
    }
}
