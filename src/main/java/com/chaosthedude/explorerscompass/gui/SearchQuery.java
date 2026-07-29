package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A parsed list filter. Positive terms must all match and a match by any excluded term rejects the
 * document.
 */
@OnlyIn(Dist.CLIENT)
final class SearchQuery {

	enum Field {
		ANY,
		ID,
		NAME,
		SOURCE,
		GROUP,
		DIMENSION
	}

	private final List<SearchTerm> terms;

	private SearchQuery(List<SearchTerm> terms) {
		this.terms = terms;
	}

	static SearchQuery parse(String input) {
		final List<SearchTerm> terms = new ArrayList<SearchTerm>();
		for (String token : tokenize(input)) {
			addTerm(terms, token);
		}
		return new SearchQuery(List.copyOf(terms));
	}

	boolean matches(SearchDocument document) {
		for (SearchTerm term : terms) {
			final boolean matches = document.contains(term.field, term.value);
			if (term.excluded ? matches : !matches) {
				return false;
			}
		}
		return true;
	}

	private static void addTerm(List<SearchTerm> terms, String token) {
		boolean excluded = token.startsWith("-") && token.length() > 1;
		String value = excluded ? token.substring(1) : token;
		Field field = Field.ANY;

		if (value.startsWith("@")) {
			field = Field.SOURCE;
			value = value.substring(1);
		} else if (value.startsWith("#")) {
			field = Field.GROUP;
			value = value.substring(1);
		} else {
			final int separator = value.indexOf(':');
			if (separator > 0) {
				final Field prefixedField = fieldForPrefix(value.substring(0, separator));
				if (prefixedField != null) {
					field = prefixedField;
					value = value.substring(separator + 1);
				}
			}
		}

		value = SearchDocument.normalize(value);
		if (!value.isEmpty() && !value.equals("-")) {
			terms.add(new SearchTerm(field, value, excluded));
		}
	}

	private static Field fieldForPrefix(String prefix) {
		return switch (SearchDocument.normalize(prefix)) {
			case "id" -> Field.ID;
			case "name" -> Field.NAME;
			case "mod" -> Field.SOURCE;
			case "group" -> Field.GROUP;
			case "dim" -> Field.DIMENSION;
			default -> null;
		};
	}

	/**
	 * Splits on whitespace outside quotes. Quotes are removed, and an unclosed quote consumes the
	 * rest of the input so that filtering remains useful while a phrase is still being typed.
	 */
	private static List<String> tokenize(String input) {
		final List<String> tokens = new ArrayList<String>();
		final StringBuilder token = new StringBuilder();
		boolean quoted = false;
		boolean escaped = false;

		for (int i = 0; i < input.length(); i++) {
			final char character = input.charAt(i);
			if (escaped) {
				token.append(character);
				escaped = false;
			} else if (character == '\\' && quoted) {
				escaped = true;
			} else if (character == '"') {
				quoted = !quoted;
			} else if (Character.isWhitespace(character) && !quoted) {
				addToken(tokens, token);
			} else {
				token.append(character);
			}
		}
		if (escaped) {
			token.append('\\');
		}
		addToken(tokens, token);
		return tokens;
	}

	private static void addToken(List<String> tokens, StringBuilder token) {
		if (token.length() > 0) {
			tokens.add(token.toString());
			token.setLength(0);
		}
	}

	private static class SearchTerm {

		private final Field field;
		private final String value;
		private final boolean excluded;

		private SearchTerm(Field field, String value, boolean excluded) {
			this.field = field;
			this.value = value;
			this.excluded = excluded;
		}

	}

}
