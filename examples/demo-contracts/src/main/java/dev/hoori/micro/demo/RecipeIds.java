package dev.hoori.micro.demo;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;
import java.util.List;

/** Bounded batch input; order and duplicates are meaningful. */
public record RecipeIds(List<Long> ids) {
    public RecipeIds {
        if (ids == null || ids.size() > 16) throw new JsonException("At most 16 recipe IDs");

        ids = List.copyOf(ids);
        for (long id : ids) if (id < 1) throw new JsonException("Invalid recipe ID");
    }

    public static final JsonCodec<RecipeIds> CODEC = new JsonCodec<>() {
        public RecipeIds read(JsonReader input) {
            List<Long> ids = null;
            input.beginObject();
            while (input.hasNext()) {
                if (!input.nextName().equals("ids")) input.skipValue();
                else {
                    ids = new ArrayList<>();
                    input.beginArray();
                    while (input.hasNext()) {
                        if (ids.size() == 16) throw new JsonException("At most 16 recipe IDs");

                        ids.add(input.nextLong());
                    }
                    input.endArray();
                }
            }
            input.endObject();

            return new RecipeIds(ids);
        }

        public void write(RecipeIds value, JsonWriter output) {
            output.beginObject().name("ids").beginArray();
            for (long id : value.ids()) output.value(id);
            output.endArray().endObject();
        }
    };
}
