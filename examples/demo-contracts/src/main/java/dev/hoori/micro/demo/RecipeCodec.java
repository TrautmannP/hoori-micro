package dev.hoori.micro.demo;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;

/** Explicit codec, additive response fields tolerated; required fields stay validated. */
public final class RecipeCodec implements JsonCodec<Recipe> {
    public static final RecipeCodec INSTANCE = new RecipeCodec();

    private RecipeCodec() {}

    @Override
    public Recipe read(JsonReader input) {
        long id = -1;
        String title = null;
        input.beginObject();
        while (input.hasNext()) {
            String name = input.nextName();

            if (name.equals("id")) id = input.nextLong();
            else if (name.equals("title")) title = input.nextString();
            else input.skipValue();
        }
        input.endObject();

        if (id < 1 || title == null || title.isEmpty() || title.length() > 200)
            throw new JsonException("Invalid recipe");

        return new Recipe(id, title);
    }

    @Override
    public void write(Recipe value, JsonWriter output) {
        output.beginObject()
                .name("id")
                .value(value.id)
                .name("title")
                .value(value.title)
                .endObject();
    }
}
