package dev.romoslayer.stormcell.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough TOML for a config file people edit by hand: comments, [tables] (including quoted parts such as
 * [biomeOverrides."minecraft:desert"]), and key = value lines holding a boolean, a number, a "string" or a one-line
 * ["list", "of", "strings"]. As in TOML, a table may be defined only once and a key set only once per table.
 * Anything outside this subset (inline tables, multi-line strings or arrays, dates, inf and nan, numbers too large
 * for a double) is reported as an error rather than guessed at.
 */
public final class Toml {
	private Toml() {
	}

	public static final class ParseException extends Exception {
		public ParseException(int line, String message) {
			super("line " + line + ": " + message);
		}
	}

	/** Every table in the file, keyed by its path (the parts of its header), in file order. */
	public static Map<List<String>, Map<String, Object>> parse(String text) throws ParseException {
		Map<List<String>, Map<String, Object>> tables = new LinkedHashMap<>();
		Map<String, Object> current = new LinkedHashMap<>();
		tables.put(List.of(), current);
		String[] lines = text.split("\r?\n", -1);
		for (int i = 0; i < lines.length; i++) {
			int lineNumber = i + 1;
			String line = stripComment(lines[i]).trim();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("[")) {
				if (!line.endsWith("]") || line.startsWith("[[")) {
					throw new ParseException(lineNumber, "malformed table header: " + line);
				}
				List<String> path = splitPath(line.substring(1, line.length() - 1), lineNumber);
				if (tables.containsKey(path)) {
					throw new ParseException(lineNumber, "table [" + String.join(".", path) + "] is defined twice");
				}
				current = new LinkedHashMap<>();
				tables.put(path, current);
				continue;
			}
			int equals = indexOutsideQuotes(line, '=');
			if (equals <= 0) {
				throw new ParseException(lineNumber, "expected key = value: " + line);
			}
			String key = unquoteKey(line.substring(0, equals).trim(), lineNumber);
			if (current.containsKey(key)) {
				throw new ParseException(lineNumber, "key '" + key + "' is set twice in the same table");
			}
			current.put(key, parseValue(line.substring(equals + 1).trim(), lineNumber));
		}
		return tables;
	}

	private static String stripComment(String line) {
		int hash = indexOutsideQuotes(line, '#');
		return hash < 0 ? line : line.substring(0, hash);
	}

	private static int indexOutsideQuotes(String text, char wanted) {
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && quoted) {
				i++;
			} else if (c == '"') {
				quoted = !quoted;
			} else if (c == wanted && !quoted) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> splitPath(String header, int line) throws ParseException {
		List<String> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < header.length(); i++) {
			char c = header.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (c == '.' && !quoted) {
				parts.add(checkPart(part.toString().trim(), line));
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		if (quoted) {
			throw new ParseException(line, "unterminated quote in table header");
		}
		parts.add(checkPart(part.toString().trim(), line));
		return List.copyOf(parts);
	}

	private static String checkPart(String part, int line) throws ParseException {
		if (part.isEmpty()) {
			throw new ParseException(line, "empty part in table header");
		}
		return part;
	}

	private static String unquoteKey(String key, int line) throws ParseException {
		if (key.startsWith("\"")) {
			return (String) parseValue(key, line);
		}
		return key;
	}

	private static Object parseValue(String value, int line) throws ParseException {
		if (value.isEmpty()) {
			throw new ParseException(line, "missing value");
		}
		if (value.equals("true") || value.equals("false")) {
			return Boolean.parseBoolean(value);
		}
		if (value.startsWith("\"")) {
			if (value.length() < 2 || !value.endsWith("\"")) {
				throw new ParseException(line, "unterminated string: " + value);
			}
			return unescape(value.substring(1, value.length() - 1));
		}
		if (value.startsWith("[")) {
			if (!value.endsWith("]")) {
				throw new ParseException(line, "lists must open and close on one line: " + value);
			}
			List<Object> list = new ArrayList<>();
			String inner = value.substring(1, value.length() - 1).trim();
			while (!inner.isEmpty()) {
				int comma = indexOutsideQuotes(inner, ',');
				String item = (comma < 0 ? inner : inner.substring(0, comma)).trim();
				if (!item.isEmpty()) {
					list.add(parseValue(item, line));
				}
				inner = comma < 0 ? "" : inner.substring(comma + 1).trim();
			}
			return list;
		}
		try {
			String number = value.replace("_", "");
			if (number.contains(".") || number.contains("e") || number.contains("E")) {
				double parsed = Double.parseDouble(number);
				if (!Double.isFinite(parsed)) {
					throw new ParseException(line, "number out of range: " + value);
				}
				return parsed;
			}
			return Long.parseLong(number);
		} catch (NumberFormatException e) {
			throw new ParseException(line, "not a boolean, number, \"string\" or [list]: " + value);
		}
	}

	private static String unescape(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				char next = text.charAt(++i);
				switch (next) {
					case 'n' -> out.append('\n');
					case 't' -> out.append('\t');
					default -> out.append(next);
				}
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	public static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A table header part, quoted only when it needs to be. */
	public static String headerPart(String part) {
		return part.matches("[A-Za-z0-9_-]+") ? part : quote(part);
	}
}
