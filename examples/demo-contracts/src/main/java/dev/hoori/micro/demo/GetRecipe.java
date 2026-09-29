package dev.hoori.micro.demo;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonToken;
import hoori.rest.json.JsonWriter;

/** Action input. Accepts the id as JSON number or as decimal string (gateway path parameters). */
public final class GetRecipe {
    public final long id;

    public GetRecipe(long id) {
        this.id = id;
    }

    public static final JsonCodec<GetRecipe> CODEC = new JsonCodec<>() {
        @Override
        public GetRecipe read(JsonReader input) {
            long id = -1;
            input.beginObject();
            while (input.hasNext()) {
                if (!input.nextName().equals("id")) input.skipValue();
                else if (input.peek() == JsonToken.NUMBER) id = input.nextLong();
                else {
                    String text = input.nextString();
                    try {
                        id = text == null || text.startsWith("+") ? -1 : Long.parseLong(text);
                    } catch (NumberFormatException invalid) {
                        throw new JsonException("Invalid recipe ID");
                    }
                }
            }
            input.endObject();

            if (id < 1) throw new JsonException("Invalid recipe ID");

            return new GetRecipe(id);
        }

        @Override
        public void write(GetRecipe value, JsonWriter output) {
            output.beginObject().name("id").value(value.id).endObject();
        }
    };
}
