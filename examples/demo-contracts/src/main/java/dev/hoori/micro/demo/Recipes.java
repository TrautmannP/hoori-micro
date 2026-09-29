package dev.hoori.micro.demo;

import hoori.micro.Action;

/** Optional typed contract of the recipes actions: names and wire codecs, no hosts or routes. */
public final class Recipes {
    private Recipes() {}

    public static final Action<GetRecipe, Recipe> GET =
            new Action<>("recipes.get", GetRecipe.CODEC, RecipeCodec.INSTANCE);
    public static final Action<GetRecipe, Recipe> SLOW =
            new Action<>("recipes.slow", GetRecipe.CODEC, RecipeCodec.INSTANCE);
}
