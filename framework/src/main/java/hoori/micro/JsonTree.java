package hoori.micro;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Untyped JSON for generic calls: Map (insertion order), List, String, Long, Double, Boolean or
 * null. Integral numbers outside long become Double. Application DTOs use the generated MVC adapters.
 */
public final class JsonTree implements JsonCodec<Object> {
    public static final JsonTree CODEC = new JsonTree();

    private JsonTree() {}

    @Override
    public Object read(JsonReader input) {
        switch (input.peek()) {
            case BEGIN_OBJECT: {
                Map<String, Object> map = new LinkedHashMap<>();
                input.beginObject();
                while (input.hasNext()) {
                    String name = input.nextName();
                    map.put(name, read(input));
                }
                input.endObject();

                return map;
            }
            case BEGIN_ARRAY: {
                List<Object> list = new ArrayList<>();
                input.beginArray();
                while (input.hasNext()) list.add(read(input));
                input.endArray();

                return list;
            }
            case STRING:
                return input.nextString();
            case BOOLEAN:
                return input.nextBoolean();
            case NULL:
                input.nextNull();

                return null;
            case NUMBER: {
                String number = input.nextNumber();

                if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(number);
                    } catch (NumberFormatException outsideLong) {
                        /* Fall through to double. */
                    }
                }

                double value = Double.parseDouble(number);

                if (value == Double.POSITIVE_INFINITY || value == Double.NEGATIVE_INFINITY)
                    throw new JsonException("Non-finite JSON number");

                return value;
            }
            default:
                throw new JsonException("Unexpected JSON token");
        }
    }

    @Override
    public void write(Object value, JsonWriter output) {
        if (value == null) output.nullValue();
        else if (value instanceof String text) output.value(text);
        else if (value instanceof Boolean flag) output.value(flag);
        else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            output.value(((Number) value).longValue());
        else if (value instanceof Double || value instanceof Float) output.value(((Number) value).doubleValue());
        else if (value instanceof Map<?, ?> map) {
            output.beginObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name))
                    throw new IllegalArgumentException("JSON names are strings");

                output.name(name);
                write(entry.getValue(), output);
            }
            output.endObject();
        } else if (value instanceof List<?> list) {
            output.beginArray();
            for (Object item : list) write(item, output);
            output.endArray();
        } else throw new IllegalArgumentException("Unsupported JSON value type");
    }
}
