package dev.hoori.micro.data.service;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.data.client.*;
import dev.hoori.micro.data.dto.*;
import hoori.concurrent.Tasks;
import hoori.micro.app.Service;

@Service
public final class DraftService {
    private final RecipeClient recipes;
    private final PantryClient pantry;
    private final DraftWriter writer;

    public DraftService(RecipeClient recipes, PantryClient pantry, DraftWriter writer) {
        this.recipes = recipes;
        this.pantry = pantry;
        this.writer = writer;
    }

    public SavedDraft save(Draft input) throws Exception {
        Overview prepared = Tasks.parallel(
                        Tasks.task(recipes::get, input.recipeId()), Tasks.task(pantry::items, input.recipeId()))
                .named("drafts.prepare")
                .failFast()
                .map(Overview::new);
        // The injected interface returns only after the physical local commit.
        writer.save(input.id(), prepared);

        return new SavedDraft(input.id(), "saved");
    }
}
