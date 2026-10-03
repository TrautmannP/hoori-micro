package dev.hoori.micro.demo;

import hoori.micro.Action;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;
import java.util.List;

/** Optional typed contract of the recipes actions: names and wire codecs, no hosts or routes. */
public final class Recipes {
    private Recipes() {}

    public static final Action<GetRecipe, Recipe> GET =
            new Action<>("recipes.get", GetRecipe.CODEC, RecipeCodec.INSTANCE);
    public static final Action<GetRecipe, Recipe> SLOW =
            new Action<>("recipes.slow", GetRecipe.CODEC, RecipeCodec.INSTANCE);
    public static final JsonCodec<List<Recipe>> LIST = new JsonCodec<>() {
        public List<Recipe> read(JsonReader input) {
            List<Recipe> recipes = new ArrayList<>();
            input.beginArray();
            while (input.hasNext()) {
                if (recipes.size() == 16) throw new JsonException("At most 16 recipes");

                recipes.add(RecipeCodec.INSTANCE.read(input));
            }
            input.endArray();

            return List.copyOf(recipes);
        }

        public void write(List<Recipe> value, JsonWriter output) {
            if (value.size() > 16) throw new JsonException("At most 16 recipes");

            output.beginArray();
            for (Recipe recipe : value) RecipeCodec.INSTANCE.write(recipe, output);
            output.endArray();
        }
    };
    public static final Action<RecipeIds, List<Recipe>> GET_MANY =
            new Action<>("recipes.get-many", RecipeIds.CODEC, LIST);
}
