package hoori.micro.openapi;

import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded contract JSON using the SDK parser, preserving exact number tokens. */
public final class ContractJson {
    public static final int MAX_BYTES = 65536;
    public static final JsonLimits LIMITS = new JsonLimits(32, 16384, 128, MAX_BYTES);
    private static final JsonCodec<Object> CODEC = new JsonCodec<>() {
        @Override
        public Object read(JsonReader input) {
            return switch (input.peek()) {
                case BEGIN_OBJECT -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    input.beginObject();
                    while (input.hasNext()) {
                        if (result.size() == 512) throw new JsonException("Contract object member limit");

                        String name = input.nextName();

                        if (result.containsKey(name)) throw new JsonException("Duplicate contract member");

                        result.put(name, read(input));
                    }
                    input.endObject();
                    yield result;
                }
                case BEGIN_ARRAY -> {
                    List<Object> result = new ArrayList<>();
                    input.beginArray();
                    while (input.hasNext()) {
                        if (result.size() == 512) throw new JsonException("Contract array limit");

                        result.add(read(input));
                    }
                    input.endArray();
                    yield result;
                }
                case STRING -> input.nextString();
                case NUMBER -> new NumberToken(input.nextNumber());
                case BOOLEAN -> input.nextBoolean();
                case NULL -> {
                    input.nextNull();
                    yield null;
                }
                default -> throw new JsonException("Invalid contract JSON");
            };
        }

        @Override
        public void write(Object value, JsonWriter output) {
            if (value == null) output.nullValue();
            else if (value instanceof String text) output.value(text);
            else if (value instanceof NumberToken number) output.number(number.value());
            else if (value instanceof Boolean flag) output.value(flag);
            else if (value instanceof Integer || value instanceof Long) output.value(((Number) value).longValue());
            else if (value instanceof List<?> list) {
                output.beginArray();
                for (Object item : list) write(item, output);
                output.endArray();
            } else if (value instanceof Map<?, ?> map) {
                if (map.size() > 512) throw new JsonException("Contract object member limit");

                output.beginObject();
                List<String> keys = new ArrayList<>();
                for (Object key : map.keySet()) {
                    if (!(key instanceof String text)) throw new JsonException("Contract object key");

                    keys.add(text);
                }
                // ponytail: bounded insertion sort (512 keys); no guest sort dependency.
                for (int i = 1; i < keys.size(); i++) {
                    String key = keys.get(i);
                    int j = i;
                    while (j > 0 && keys.get(j - 1).compareTo(key) > 0) {
                        keys.set(j, keys.get(j - 1));
                        j--;
                    }
                    keys.set(j, key);

                    if ((i & 63) == 0) Thread.yield();
                }
                for (String key : keys) {
                    output.name(key);
                    write(map.get(key), output);
                }
                output.endObject();
            } else throw new JsonException("Unsupported contract value");
        }
    };

    private ContractJson() {}

    public record NumberToken(String value) {}

    public static Object read(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new JsonException("OpenAPI document byte limit");

        return Json.decode(bytes, CODEC, LIMITS);
    }

    public static byte[] source(java.io.InputStream input) throws java.io.IOException {
        byte[] bytes = new byte[MAX_BYTES + 1];
        int length = 0, count;
        while (length < bytes.length && (count = input.read(bytes, length, bytes.length - length)) != -1)
            length += count;

        if (length > MAX_BYTES) throw new java.io.IOException("OpenAPI document byte limit");

        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, 0, length);

        return result;
    }

    public static byte[] bytes(Object value) {
        return Json.encode(value, CODEC, LIMITS);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected contract object");

        return (Map<String, Object>) value;
    }

    public static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected contract array");

        return list;
    }

    public static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected contract string");

        return text;
    }

    public static String hash(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder text = new StringBuilder();
            for (byte item : digest) {
                text.append("0123456789abcdef".charAt((item & 255) >>> 4));
                text.append("0123456789abcdef".charAt(item & 15));
            }

            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    public static boolean isHash(String value) {
        if (value == null || value.length() != 64) return false;

        for (int i = 0; i < value.length(); i++) if ("0123456789abcdef".indexOf(value.charAt(i)) < 0) return false;

        return true;
    }
}
