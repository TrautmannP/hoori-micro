package dev.hoori.micro.data;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;

public record Draft(long id, long recipeId) {
    public Draft {
        if (id < 1 || recipeId < 1) throw new JsonException("Invalid draft/recipe ID");
    }

    public static final JsonCodec<Draft> CODEC = new JsonCodec<>() {
        public Draft read(JsonReader input) {
            long id = 0, recipeId = 0;
            input.beginObject();
            while (input.hasNext()) {
                String name = input.nextName();

                if (name.equals("id")) id = input.nextLong();
                else if (name.equals("recipeId")) recipeId = input.nextLong();
                else input.skipValue();
            }
            input.endObject();

            return new Draft(id, recipeId);
        }

        public void write(Draft value, JsonWriter output) {
            output.beginObject()
                    .name("id")
                    .value(value.id())
                    .name("recipeId")
                    .value(value.recipeId())
                    .endObject();
        }
    };
}
