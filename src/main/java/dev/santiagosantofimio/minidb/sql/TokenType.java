package dev.santiagosantofimio.minidb.sql;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum TokenType {
    IDENTIFIER("an identifier", false),
    INTEGER("an integer", false),
    DECIMAL("a number", false),
    STRING("a string", false),

    SELECT,
    FROM,
    WHERE,
    AS,
    INSERT,
    INTO,
    VALUES,
    CREATE,
    TABLE,
    DROP,
    AND,
    OR,
    NOT,
    NULL,
    IS,
    BETWEEN,
    TRUE,
    FALSE,
    INT,
    DOUBLE,
    BOOLEAN,
    VARCHAR,
    PRIMARY,
    KEY,
    INDEX,
    ON,

    COMMA("','", false),
    DOT("'.'", false),
    SEMICOLON("';'", false),
    LEFT_PAREN("'('", false),
    RIGHT_PAREN("')'", false),
    STAR("'*'", false),
    PLUS("'+'", false),
    MINUS("'-'", false),
    SLASH("'/'", false),
    EQUALS("'='", false),
    NOT_EQUALS("'<>'", false),
    LESS("'<'", false),
    LESS_OR_EQUAL("'<='", false),
    GREATER("'>'", false),
    GREATER_OR_EQUAL("'>='", false),

    END("the end of the input", false);

    private static final Map<String, TokenType> KEYWORDS = Arrays.stream(values())
            .filter(TokenType::isKeyword)
            .collect(Collectors.toUnmodifiableMap(
                    type -> type.name().toLowerCase(Locale.ROOT), Function.identity()));

    private final String description;
    private final boolean keyword;

    TokenType() {
        this.description = name();
        this.keyword = true;
    }

    TokenType(String description, boolean keyword) {
        this.description = description;
        this.keyword = keyword;
    }

    public boolean isKeyword() {
        return keyword;
    }

    public String description() {
        return description;
    }

    static Optional<TokenType> keyword(String lowercaseWord) {
        return Optional.ofNullable(KEYWORDS.get(lowercaseWord));
    }
}
