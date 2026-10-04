package dev.hoori.micro.demo;

import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.validation.Violation;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs unchanged on Java 21 and the pinned Hoori runtime, without build-time processors. */
public final class CodecChecks {
    public static void main(String[] args) {
        for (String value : List.of("1", "\"1\"", "\"01\"")) {
            var input = Json.decode(bytes("{\"id\":" + value + ",\"extra\":true}"), GetRecipeJsonCodec.INSTANCE);
            check(input.id() == 1 && DemoValidation.GET_RECIPE.validate(input).isEmpty());
        }
        for (String input : List.of(
                "{}",
                "{\"id\":null}",
                "{\"id\":1,\"id\":2}",
                "{\"id\":\"+1\"}",
                "{\"id\":\"9223372036854775808\"}",
                "{\"id\":1} false")) {
            try {
                Json.decode(bytes(input), GetRecipeJsonCodec.INSTANCE);
                throw new AssertionError("Invalid wire input accepted");
            } catch (JsonException expected) {
                // Structural errors remain codec errors, separate from semantic validation.
            }
        }
        var invalid = Json.decode(bytes("{\"id\":0}"), GetRecipeJsonCodec.INSTANCE);
        check(DemoValidation.GET_RECIPE.validate(invalid).equals(List.of(new Violation("id", "positive"))));
        check(DemoValidation.GET_RECIPE.validate(null).equals(List.of(new Violation("", "not_null"))));
        var ids = Json.decode(bytes("{\"ids\":[1,0,null]}"), RecipeIdsJsonCodec.INSTANCE);
        check(DemoValidation.RECIPE_IDS
                .validate(ids)
                .equals(List.of(new Violation("ids[1]", "positive"), new Violation("ids[2]", "not_null"))));
        check(DemoValidation.RECIPE_IDS
                .validate(new RecipeIds(null))
                .equals(List.of(new Violation("ids", "not_null"))));
        try {
            Json.decode(bytes("{\"ids\":[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]}"), RecipeIdsJsonCodec.INSTANCE);
            throw new AssertionError("Unbounded list accepted");
        } catch (JsonException expected) {
            // Reject while reading, before constructing the DTO or invoking a handler.
        }
        var overview = new Overview(new Recipe(1, "Kartoffelsuppe"), List.of("Möhren"));
        check(overview.equals(
                Json.decode(Json.encode(overview, OverviewJsonCodec.INSTANCE), OverviewJsonCodec.INSTANCE)));
        check(new String(Json.encode(new Recipe(1, "Kartoffelsuppe"), RecipeJsonCodec.INSTANCE), StandardCharsets.UTF_8)
                .equals("{\"id\":1,\"title\":\"Kartoffelsuppe\"}"));
        System.out.println("Generated DTO codec/validation checks passed");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("DTO contract");
    }
}
