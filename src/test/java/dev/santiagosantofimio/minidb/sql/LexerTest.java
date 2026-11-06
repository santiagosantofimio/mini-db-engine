package dev.santiagosantofimio.minidb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.SyntaxException;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {

    private static List<TokenType> types(String source) {
        return Lexer.tokenize(source).stream().map(Token::type).toList();
    }

    private static Token only(String source) {
        List<Token> tokens = Lexer.tokenize(source);
        assertEquals(2, tokens.size());
        return tokens.get(0);
    }

    @Test
    void keywordsAreCaseInsensitiveAndIdentifiersAreLowercased() {
        List<Token> tokens = Lexer.tokenize("select Name FROM Students");

        assertEquals(
                List.of(TokenType.SELECT, TokenType.IDENTIFIER, TokenType.FROM, TokenType.IDENTIFIER, TokenType.END),
                tokens.stream().map(Token::type).toList());
        assertEquals("name", tokens.get(1).text());
        assertEquals("students", tokens.get(3).text());
    }

    @Test
    void recognizesEveryOperator() {
        assertEquals(
                List.of(TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.NOT_EQUALS, TokenType.LESS,
                        TokenType.LESS_OR_EQUAL, TokenType.GREATER, TokenType.GREATER_OR_EQUAL, TokenType.PLUS,
                        TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.LEFT_PAREN,
                        TokenType.RIGHT_PAREN, TokenType.COMMA, TokenType.DOT, TokenType.SEMICOLON, TokenType.END),
                types("= <> != < <= > >= + - * / ( ) , . ;"));
    }

    @Test
    void distinguishesIntegersFromDecimals() {
        assertEquals(TokenType.INTEGER, only("42").type());
        assertEquals(TokenType.DECIMAL, only("4.25").type());
        assertEquals(TokenType.DECIMAL, only("1e3").type());
        assertEquals(TokenType.DECIMAL, only("2.5E-4").type());
        assertEquals(List.of(TokenType.INTEGER, TokenType.DOT, TokenType.END), types("1."));
    }

    @Test
    void stringsUnescapeDoubledQuotes() {
        Token token = only("'it''s here'");

        assertEquals(TokenType.STRING, token.type());
        assertEquals("it's here", token.text());
    }

    @Test
    void tracksLineAndColumnOfEachToken() {
        List<Token> tokens = Lexer.tokenize("SELECT a,\n  bé, c\nFROM t");

        assertEquals(new SourcePosition(1, 8), tokens.get(1).position());
        assertEquals(new SourcePosition(2, 3), tokens.get(3).position());
        assertEquals(new SourcePosition(2, 7), tokens.get(5).position());
        assertEquals(new SourcePosition(3, 1), tokens.get(6).position());
    }

    @Test
    void countsCharactersOutsideTheBasicPlaneOnce() {
        List<Token> tokens = Lexer.tokenize("'🦤' x");

        assertEquals(new SourcePosition(1, 5), tokens.get(1).position());
    }

    @Test
    void skipsLineComments() {
        assertEquals(
                List.of(TokenType.SELECT, TokenType.STAR, TokenType.END),
                types("SELECT -- everything\n*"));
    }

    @Test
    void reportsUnterminatedStringsAtTheOpeningQuote() {
        SyntaxException error = assertThrows(SyntaxException.class, () -> Lexer.tokenize("SELECT 'abc"));

        assertEquals(new SourcePosition(1, 8), error.position().orElseThrow());
    }

    @Test
    void reportsUnexpectedCharacters() {
        SyntaxException error = assertThrows(SyntaxException.class, () -> Lexer.tokenize("SELECT #"));

        assertTrue(error.getMessage().contains("'#'"));
        assertEquals(new SourcePosition(1, 8), error.position().orElseThrow());
    }

    @Test
    void rejectsMalformedNumbersAndHugeIdentifiers() {
        assertThrows(SyntaxException.class, () -> Lexer.tokenize("12abc"));
        assertThrows(SyntaxException.class, () -> Lexer.tokenize("x".repeat(Lexer.MAX_IDENTIFIER_LENGTH + 1)));
    }

    @Test
    void statementIsCompleteOnlyAfterASemicolonOutsideStrings() {
        assertFalse(Lexer.isComplete("SELECT *"));
        assertFalse(Lexer.isComplete("SELECT * FROM t WHERE name = 'a;"));
        assertFalse(Lexer.isComplete("SELECT 1 -- done;"));
        assertTrue(Lexer.isComplete("SELECT 1; -- done"));
        assertTrue(Lexer.isComplete("SELECT 1; SELECT 2;"));
        assertTrue(Lexer.isComplete("SELECT 1;   \n"));
        assertTrue(Lexer.isComplete("SELECT # ;"));
        assertFalse(Lexer.isComplete(""));
    }
}
