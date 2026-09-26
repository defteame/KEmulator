package emulator.ui.headless;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough JSON writing for report.json. */
final class Json {
	private Json() {
	}

	static Map<String, Object> object() {
		return new LinkedHashMap<String, Object>();
	}

	static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void indent(StringBuilder sb, int level) {
		sb.append('\n');
		for (int i = 0; i < level; i++) {
			sb.append("  ");
		}
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object v, int level) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String) {
			string(sb, (String) v);
		} else if (v instanceof Double || v instanceof Float) {
			double d = ((Number) v).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				sb.append("null");
			} else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				sb.append((long) d);
			} else {
				sb.append(String.format(java.util.Locale.ROOT, "%.3f", d));
			}
		} else if (v instanceof Number || v instanceof Boolean) {
			sb.append(v);
		} else if (v instanceof Map) {
			Map<String, Object> m = (Map<String, Object>) v;
			if (m.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> e : m.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				indent(sb, level + 1);
				string(sb, e.getKey());
				sb.append(": ");
				write(sb, e.getValue(), level + 1);
			}
			indent(sb, level);
			sb.append('}');
		} else if (v instanceof Collection) {
			Collection<Object> c = (Collection<Object>) v;
			if (c.isEmpty()) {
				sb.append("[]");
				return;
			}
			sb.append('[');
			boolean first = true;
			for (Object o : c) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				indent(sb, level + 1);
				write(sb, o, level + 1);
			}
			indent(sb, level);
			sb.append(']');
		} else {
			string(sb, v.toString());
		}
	}

	private static void string(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}
}
