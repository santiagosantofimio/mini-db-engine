package dev.santiagosantofimio.minidb.sql;

import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.SyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Lexer {

    public static final int MAX_IDENTIFIER_LENGTH = 128;

    private final String source;
    private int index;
    private int line = 1;
    private int column = 1;
    private boolean insideUnterminatedString;
    private SourcePosition endOfLastToken = new SourcePosition(1, 1);

    private Lexer(String source) {
        this.source = source;
    }

    public static List<Token> tokenize(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = new ArrayList<>();
        Token token;
        do {
            token = lexer.nextToken();
            tokens.add(token);
        } while (token.type() != TokenType.END);
        return tokens;
    }

    public static boolean isComplete(String source) {
        Lexer lexer = new Lexer(source);
        TokenType last = null;
        try {
            for (Token token = lexer.nextToken(); token.type() != TokenType.END; token = lexer.nextToken()) {
                last = token.type();
            }
        } catch (SyntaxException e) {
            return !lexer.insideUnterminatedString && source.stripTrailing().endsWith(";");
        }
        return last == TokenType.SEMICOLON;
    }

    private Token nextToken() {
        skipWhitespaceAndComments();
        if (atEnd()) {
            return new Token(TokenType.END, "", endOfLastToken);
        }
        Token token = scanToken(position());
        endOfLastToken = position();
        return token;
    }

    private Token scanToken(SourcePosition start) {
        char c = peek();
        if (Character.isLetter(c) || c == '_') {
            return word(start);
        }
        if (isDigit(c)) {
            return number(start);
        }
        if (c == '\'') {
            return string(start);
        }
        return symbol(start);
    }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            if (Character.isWhitespace(peek())) {
                advance();
            } else if (peek() == '-' && peekNext() == '-') {
                while (!atEnd() && peek() != '\n') {
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private Token word(SourcePosition start) {
        int begin = index;
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            advance();
        }
        String word = source.substring(begin, index);
        String lowercase = word.toLowerCase(Locale.ROOT);
        return TokenType.keyword(lowercase)
                .map(keyword -> new Token(keyword, word, start))
                .orElseGet(() -> identifier(lowercase, start));
    }

    private static Token identifier(String name, SourcePosition start) {
        if (name.length() > MAX_IDENTIFIER_LENGTH) {
            throw new SyntaxException(
                    "identifiers can have at most " + MAX_IDENTIFIER_LENGTH + " characters", start);
        }
        return new Token(TokenType.IDENTIFIER, name, start);
    }

    private Token number(SourcePosition start) {
        int begin = index;
        boolean decimal = false;
        consumeDigits();
        if (peek() == '.' && isDigit(peekNext())) {
            decimal = true;
            advance();
            consumeDigits();
        }
        if ((peek() == 'e' || peek() == 'E') && startsExponent()) {
            decimal = true;
            advance();
            if (peek() == '+' || peek() == '-') {
                advance();
            }
            consumeDigits();
        }
        if (Character.isLetter(peek()) || peek() == '_') {
            throw new SyntaxException("malformed number", start);
        }
        return new Token(decimal ? TokenType.DECIMAL : TokenType.INTEGER, source.substring(begin, index), start);
    }

    private boolean startsExponent() {
        char next = peekNext();
        if (next == '+' || next == '-') {
            return index + 2 < source.length() && isDigit(source.charAt(index + 2));
        }
        return isDigit(next);
    }

    private void consumeDigits() {
        while (isDigit(peek())) {
            advance();
        }
    }

    private Token string(SourcePosition start) {
        advance();
        StringBuilder text = new StringBuilder();
        while (true) {
            if (atEnd()) {
                insideUnterminatedString = true;
                throw new SyntaxException("this string is never closed", start);
            }
            char c = advance();
            if (c == '\'') {
                if (peek() != '\'') {
                    return new Token(TokenType.STRING, text.toString(), start);
                }
                advance();
            }
            text.append(c);
        }
    }

    private Token symbol(SourcePosition start) {
        int begin = index;
        char c = advance();
        TokenType type = switch (c) {
            case ',' -> TokenType.COMMA;
            case '.' -> TokenType.DOT;
            case ';' -> TokenType.SEMICOLON;
            case '(' -> TokenType.LEFT_PAREN;
            case ')' -> TokenType.RIGHT_PAREN;
            case '*' -> TokenType.STAR;
            case '+' -> TokenType.PLUS;
            case '-' -> TokenType.MINUS;
            case '/' -> TokenType.SLASH;
            case '=' -> TokenType.EQUALS;
            case '<' -> match('=') ? TokenType.LESS_OR_EQUAL
                    : match('>') ? TokenType.NOT_EQUALS
                    : TokenType.LESS;
            case '>' -> match('=') ? TokenType.GREATER_OR_EQUAL : TokenType.GREATER;
            case '!' -> {
                if (match('=')) {
                    yield TokenType.NOT_EQUALS;
                }
                throw new SyntaxException("unexpected character '!'; did you mean '!=' or NOT?", start);
            }
            default -> throw new SyntaxException("unexpected character '" + printable(c) + "'", start);
        };
        return new Token(type, source.substring(begin, index), start);
    }

    private SourcePosition position() {
        return new SourcePosition(line, column);
    }

    private static String printable(char c) {
        return Character.isISOControl(c) ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }

    private boolean match(char expected) {
        if (peek() != expected) {
            return false;
        }
        advance();
        return true;
    }

    private char advance() {
        char c = source.charAt(index++);
        if (c == '\n') {
            line++;
            column = 1;
        } else if (!Character.isLowSurrogate(c)) {
            column++;
        }
        return c;
    }

    private boolean atEnd() {
        return index >= source.length();
    }

    private char peek() {
        return atEnd() ? '\0' : source.charAt(index);
    }

    private char peekNext() {
        return index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
