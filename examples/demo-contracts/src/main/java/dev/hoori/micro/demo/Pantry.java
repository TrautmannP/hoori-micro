package dev.hoori.micro.demo;

import hoori.micro.Action;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;
import java.util.List;

/** Independent pantry read contract; an empty successful list means no matching stock. */
public final class Pantry {
    private Pantry() {}

    public static final JsonCodec<List<String>> ITEMS = new JsonCodec<>() {
        public List<String> read(JsonReader input) {
            List<String> items = new ArrayList<>();
            input.beginArray();
            while (input.hasNext()) {
                String item = input.nextString();

                if (items.size() == 16 || item == null || item.isEmpty() || item.length() > 200)
                    throw new JsonException("Invalid pantry items");

                items.add(item);
            }
            input.endArray();

            return List.copyOf(items);
        }

        public void write(List<String> value, JsonWriter output) {
            if (value.size() > 16) throw new JsonException("Too many pantry items");

            output.beginArray();
            for (String item : value) output.value(item);
            output.endArray();
        }
    };
    public static final Action<GetRecipe, List<String>> FOR_RECIPE =
            new Action<>("pantry.items", GetRecipeJsonCodec.INSTANCE, ITEMS, DemoValidation.GET_RECIPE);
}
