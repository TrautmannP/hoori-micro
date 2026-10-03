package dev.hoori.micro.demo;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.List;

/** Typed result composed from two independently owned read models. */
public record Overview(Recipe recipe, List<String> available) {
    public static final JsonCodec<Overview> CODEC = new JsonCodec<>() {
        public Overview read(JsonReader input) {
            Recipe recipe = null;
            List<String> available = null;
            input.beginObject();
            while (input.hasNext()) {
                String name = input.nextName();

                if (name.equals("recipe")) recipe = RecipeCodec.INSTANCE.read(input);
                else if (name.equals("available")) available = Pantry.ITEMS.read(input);
                else input.skipValue();
            }
            input.endObject();

            if (recipe == null || available == null) throw new JsonException("Incomplete overview");

            return new Overview(recipe, available);
        }

        public void write(Overview value, JsonWriter output) {
            output.beginObject().name("recipe");
            RecipeCodec.INSTANCE.write(value.recipe(), output);
            output.name("available");
            Pantry.ITEMS.write(value.available(), output);
            output.endObject();
        }
    };
}
