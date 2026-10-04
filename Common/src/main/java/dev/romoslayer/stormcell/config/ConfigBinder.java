package dev.romoslayer.stormcell.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the config classes into a commented TOML file and back. Every public field is an option: booleans, numbers,
 * strings and string lists become key = value lines, other objects become [tables], and a Map of objects becomes one
 * table per entry under a header of its own. {@link Comment} and {@link Range} document and limit the options.
 *
 * <p>Reading never throws for bad values: each problem (unknown section or key, wrong kind of value, out of range,
 * fractional whole number) is reported and the default kept.
 */
public final class ConfigBinder {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Comment {
		String value();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Range {
		double min();

		double max();
	}

	private ConfigBinder() {
	}

	// ---- Writing

	public static String write(Object root, String header) {
		StringBuilder out = new StringBuilder();
		for (String line : header.split("\n")) {
			out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
		}
		writeTable(out, List.of(), root, null);
		return out.toString();
	}

	private static void writeTable(StringBuilder out, List<String> path, Object table, String comment) {
		if (!path.isEmpty()) {
			out.append('\n');
			appendComment(out, comment);
			appendHeader(out, path);
		}
		List<Field> nested = new ArrayList<>();
		for (Field field : options(table.getClass())) {
			Object value = get(field, table);
			if (isSimple(field.getType())) {
				appendComment(out, describe(field));
				out.append(field.getName()).append(" = ").append(format(value)).append('\n');
			} else {
				nested.add(field);
			}
		}
		for (Field field : nested) {
			Object value = get(field, table);
			List<String> childPath = append(path, field.getName());
			Comment fieldComment = field.getAnnotation(Comment.class);
			if (value instanceof Map<?, ?> map) {
				// The map's own header is always written: on its own (with no entries after it) it means "empty"
				out.append('\n');
				appendComment(out, fieldComment == null ? null : fieldComment.value());
				appendHeader(out, childPath);
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					writeTable(out, append(childPath, String.valueOf(entry.getKey())), entry.getValue(), null);
				}
			} else if (value != null) {
				writeTable(out, childPath, value, fieldComment == null ? null : fieldComment.value());
			}
		}
	}

	private static void appendHeader(StringBuilder out, List<String> path) {
		out.append('[').append(String.join(".", path.stream().map(Toml::headerPart).toList())).append("]\n");
	}

	private static String describe(Field field) {
		Comment comment = field.getAnnotation(Comment.class);
		Range range = field.getAnnotation(Range.class);
		String text = comment == null ? "" : comment.value();
		if (range != null) {
			boolean whole = isWhole(field.getType());
			String limits = "Range: " + (whole ? Long.toString((long) range.min()) : number(range.min())) + " to "
					+ (whole ? Long.toString((long) range.max()) : number(range.max()));
			text = text.isEmpty() ? limits : text + "\n" + limits;
		}
		return text.isEmpty() ? null : text;
	}

	private static void appendComment(StringBuilder out, String comment) {
		if (comment == null || comment.isEmpty()) {
			return;
		}
		for (String line : comment.split("\n")) {
			out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
		}
	}

	private static String format(Object value) {
		if (value instanceof String string) {
			return Toml.quote(string);
		}
		if (value instanceof List<?> list) {
			return "[" + String.join(", ", list.stream().map(item -> Toml.quote(String.valueOf(item))).toList()) + "]";
		}
		if (value instanceof Float || value instanceof Double) {
			return number(((Number) value).doubleValue());
		}
		return String.valueOf(value);
	}

	/** A number as it should appear in the file (or in a message): never in exponent form, never throwing. */
	static String number(double value) {
		if (!Double.isFinite(value)) {
			return String.valueOf(value);
		}
		if (value == Math.rint(value) && Math.abs(value) < 1.0E15) {
			return Long.toString((long) value) + ".0";
		}
		return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
	}

	// ---- Reading

	/** Copies what the file says onto {@code root}, which starts out holding the defaults. Returns any complaints. */
	public static List<String> read(Map<List<String>, Map<String, Object>> tables, Object root) {
		List<String> problems = new ArrayList<>();
		Set<List<String>> visited = new HashSet<>();
		readTable(tables, List.of(), root, problems, visited);
		for (List<String> path : tables.keySet()) {
			if (!visited.contains(path)) {
				problems.add("Unknown section [" + String.join(".", path) + "] (ignored; check the spelling)");
			}
		}
		return problems;
	}

	private static void readTable(Map<List<String>, Map<String, Object>> tables, List<String> path, Object table, List<String> problems,
			Set<List<String>> visited) {
		visited.add(path);
		Map<String, Object> values = tables.getOrDefault(path, Map.of());
		Set<String> known = new HashSet<>();
		for (Field field : options(table.getClass())) {
			known.add(field.getName());
			List<String> childPath = append(path, field.getName());
			if (isSimple(field.getType())) {
				if (values.containsKey(field.getName())) {
					Object converted = convert(field, values.get(field.getName()), String.join(".", childPath), problems);
					if (converted != null) {
						set(field, table, converted);
					}
				}
			} else if (Map.class.isAssignableFrom(field.getType())) {
				readMap(tables, childPath, field, table, problems, visited);
			} else {
				Object child = get(field, table);
				if (child != null) {
					readTable(tables, childPath, child, problems, visited);
				}
			}
		}
		for (String key : values.keySet()) {
			if (!known.contains(key)) {
				problems.add("Unknown option '" + String.join(".", append(path, key)) + "' (ignored)");
			}
		}
	}

	/**
	 * A map is replaced by whatever entries the file has. Its header on its own, with no entries, empties it; a file
	 * with neither (an older file, say) keeps the defaults.
	 */
	private static void readMap(Map<List<String>, Map<String, Object>> tables, List<String> path, Field field, Object owner, List<String> problems,
			Set<List<String>> visited) {
		Class<?> valueType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[1];
		Map<String, Object> entries = new LinkedHashMap<>();
		boolean headerPresent = tables.containsKey(path);
		if (headerPresent) {
			visited.add(path);
			for (String key : tables.get(path).keySet()) {
				problems.add("Option '" + String.join(".", append(path, key)) + "' is not allowed directly under [" + String.join(".", path)
						+ "]; entries go in tables such as [" + String.join(".", path) + ".\"name\"] (ignored)");
			}
		}
		for (List<String> tablePath : tables.keySet()) {
			if (tablePath.size() == path.size() + 1 && tablePath.subList(0, path.size()).equals(path)) {
				try {
					Object entry = valueType.getDeclaredConstructor().newInstance();
					readTable(tables, tablePath, entry, problems, visited);
					entries.put(tablePath.getLast(), entry);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Config class " + valueType + " needs a public no-argument constructor", e);
				}
			}
		}
		if (headerPresent || !entries.isEmpty()) {
			set(field, owner, entries);
		}
	}

	private static Object convert(Field field, Object raw, String where, List<String> problems) {
		Class<?> type = field.getType();
		Range range = field.getAnnotation(Range.class);
		if (type == boolean.class) {
			if (raw instanceof Boolean) {
				return raw;
			}
		} else if (type == String.class) {
			if (raw instanceof String) {
				return raw;
			}
		} else if (type == List.class) {
			if (raw instanceof List<?> list) {
				return list.stream().map(String::valueOf).toList();
			}
		} else if (raw instanceof Number number) {
			double value = number.doubleValue();
			if (!Double.isFinite(value)) {
				problems.add("'" + where + "' is not a finite number; keeping the default");
				return null;
			}
			if (isWhole(type) && value != Math.rint(value)) {
				problems.add("'" + where + "' must be a whole number (got " + number(value) + "); keeping the default");
				return null;
			}
			if (range != null && (value < range.min() || value > range.max())) {
				double clamped = Math.max(range.min(), Math.min(range.max(), value));
				problems.add("'" + where + "' = " + number(value) + " is outside " + number(range.min()) + " to " + number(range.max())
						+ "; using " + number(clamped));
				value = clamped;
			}
			if (type == int.class) {
				return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
			}
			if (type == long.class) {
				return (long) value;
			}
			if (type == float.class) {
				return (float) value;
			}
			if (type == double.class) {
				return value;
			}
		}
		problems.add("'" + where + "' has the wrong kind of value (" + raw + "); keeping the default");
		return null;
	}

	// ---- Reflection helpers

	private static boolean isWhole(Class<?> type) {
		return type == int.class || type == long.class;
	}

	private static boolean isSimple(Class<?> type) {
		return type.isPrimitive() || type == String.class || type == List.class;
	}

	private static List<Field> options(Class<?> type) {
		List<Field> fields = new ArrayList<>();
		for (Field field : type.getFields()) {
			int modifiers = field.getModifiers();
			if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isFinal(modifiers)) {
				fields.add(field);
			}
		}
		return fields;
	}

	private static Object get(Field field, Object owner) {
		try {
			return field.get(owner);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void set(Field field, Object owner, Object value) {
		try {
			field.set(owner, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> append(List<String> path, String part) {
		List<String> result = new ArrayList<>(path);
		result.add(part);
		return List.copyOf(result);
	}
}
