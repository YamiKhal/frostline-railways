package com.yamikhal.frostlinerailways;

import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JSON fields that never fail silently.
 *
 * Why: in 1.20.1 (DataFixerUpper 6.0.8) {@code optionalFieldOf} turns a present but invalid value — a typo, a number
 * out of range, a wrong type — into the default without any error, and every record codec ignores keys it does not
 * know, so a misspelled key ("spcing") is dropped just as quietly. Either way a mistake becomes the default and
 * nobody is told.
 *
 *   optional  missing: the default. Invalid: an error naming the field, with the default as the partial result.
 *   closed    a record that reports keys it does not know (except "type", "processor_type" and "forge:..." keys, which
 *             belong to the codec that dispatches to it), with the decoded value as the partial result.
 *   record    RecordCodecBuilder.create, closed.
 *
 * What the error does depends on who reads the JSON. Frostline Railways' own datapack loaders (frostline_rail/...)
 * use resultOrPartial: the error is logged with the file and the field, and the file loads with the default.
 * Vanilla's worldgen registry loader (density functions in noise settings) accepts no errors: the world does not
 * load and the log names the file and field, as it does for any broken vanilla field.
 */
public final class StrictFields {

    private StrictFields() {
    }

    /** Field {@code name}, or {@code fallback} when it is missing; an invalid value is an error (partial: fallback). */
    public static <A> MapCodec<A> optional(Codec<A> codec, String name, A fallback) {
        return new Field<>(codec, name, Optional.of(fallback)).xmap(value -> value.orElse(fallback), Optional::of);
    }

    /** Field {@code name}, or empty when it is missing; an invalid value is an error (partial: empty). */
    public static <A> MapCodec<Optional<A>> optional(Codec<A> codec, String name) {
        return new Field<>(codec, name, Optional.empty());
    }

    /** A word that must be one of {@code values} (any case; read as lower case); anything else is an error naming the choices. */
    public static Codec<String> oneOf(String... values) {
        List<String> allowed = List.of(values);
        Function<String, DataResult<String>> check = value -> {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            return allowed.contains(lower)
                    ? DataResult.success(lower)
                    : DataResult.error(() -> "\"" + value + "\" is not one of " + allowed);
        };
        return Codec.STRING.flatXmap(check, check);
    }

    /** RecordCodecBuilder.create that reports unknown keys. */
    public static <O> Codec<O> record(Function<RecordCodecBuilder.Instance<O>, ? extends App<RecordCodecBuilder.Mu<O>, O>> builder) {
        return closed(RecordCodecBuilder.mapCodec(builder)).codec();
    }

    /** A map codec that reports keys it does not know. */
    public static <A> MapCodec<A> closed(MapCodec<A> codec) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return codec.keys(ops);
            }

            @Override
            public <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input) {
                DataResult<A> result = codec.decode(ops, input);
                Set<String> known = codec.keys(ops).map(key -> ops.getStringValue(key).result().orElse(""))
                        .collect(Collectors.toCollection(TreeSet::new));
                List<String> unknown = input.entries()
                        .map(entry -> ops.getStringValue(entry.getFirst()).result().orElse(String.valueOf(entry.getFirst())))
                        .filter(key -> !known.contains(key) && !dispatchKey(key))
                        .sorted()
                        .toList();
                if (unknown.isEmpty()) {
                    return result;
                }
                String prior = result.error().map(error -> error.message() + "; ").orElse("");
                String message = prior + "unknown field" + (unknown.size() > 1 ? "s " : " ") + unknown + " (known: " + known + ")";
                Optional<A> value = result.resultOrPartial(error -> {
                });
                return value.map(v -> DataResult.error(() -> message, v)).orElseGet(() -> DataResult.error(() -> message));
            }

            @Override
            public <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return codec.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "Closed[" + codec + "]";
            }
        };
    }

    /** Keys owned by the codec that dispatched to this one, or by Forge. */
    private static boolean dispatchKey(String key) {
        return key.equals("type") || key.equals("processor_type") || key.startsWith("forge:");
    }

    private static final class Field<A> extends MapCodec<Optional<A>> {
        private final Codec<A> codec;
        private final String name;
        private final Optional<A> partial;

        Field(Codec<A> codec, String name, Optional<A> partial) {
            this.codec = codec;
            this.name = name;
            this.partial = partial;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(ops.createString(name));
        }

        @Override
        public <T> DataResult<Optional<A>> decode(DynamicOps<T> ops, MapLike<T> input) {
            T value = input.get(name);
            if (value == null) {
                return DataResult.success(Optional.empty());
            }
            DataResult<A> parsed = codec.parse(ops, value);
            if (parsed.error().isPresent()) {
                // keep what did parse (a list minus its bad element), else the default
                Optional<A> kept = parsed.resultOrPartial(error -> {
                });
                String message = "\"" + name + "\": " + parsed.error().get().message()
                        + (kept.isPresent() ? " (kept what parsed)" : " (using the default)");
                return DataResult.error(() -> message, kept.isPresent() ? kept : partial);
            }
            return parsed.map(Optional::of);
        }

        @Override
        public <T> RecordBuilder<T> encode(Optional<A> input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return input.isPresent() ? prefix.add(name, codec.encodeStart(ops, input.get())) : prefix;
        }

        @Override
        public String toString() {
            return "StrictOptional[" + name + "]";
        }
    }
}
