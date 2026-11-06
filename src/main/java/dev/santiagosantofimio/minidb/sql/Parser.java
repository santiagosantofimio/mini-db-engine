package dev.santiagosantofimio.minidb.sql;

import static dev.santiagosantofimio.minidb.sql.TokenType.AND;
import static dev.santiagosantofimio.minidb.sql.TokenType.AS;
import static dev.santiagosantofimio.minidb.sql.TokenType.BETWEEN;
import static dev.santiagosantofimio.minidb.sql.TokenType.COMMA;
import static dev.santiagosantofimio.minidb.sql.TokenType.CREATE;
import static dev.santiagosantofimio.minidb.sql.TokenType.DECIMAL;
import static dev.santiagosantofimio.minidb.sql.TokenType.DOT;
import static dev.santiagosantofimio.minidb.sql.TokenType.DROP;
import static dev.santiagosantofimio.minidb.sql.TokenType.END;
import static dev.santiagosantofimio.minidb.sql.TokenType.FROM;
import static dev.santiagosantofimio.minidb.sql.TokenType.IDENTIFIER;
import static dev.santiagosantofimio.minidb.sql.TokenType.INSERT;
import static dev.santiagosantofimio.minidb.sql.TokenType.INTEGER;
import static dev.santiagosantofimio.minidb.sql.TokenType.INTO;
import static dev.santiagosantofimio.minidb.sql.TokenType.IS;
import static dev.santiagosantofimio.minidb.sql.TokenType.LEFT_PAREN;
import static dev.santiagosantofimio.minidb.sql.TokenType.MINUS;
import static dev.santiagosantofimio.minidb.sql.TokenType.NOT;
import static dev.santiagosantofimio.minidb.sql.TokenType.NULL;
import static dev.santiagosantofimio.minidb.sql.TokenType.OR;
import static dev.santiagosantofimio.minidb.sql.TokenType.PLUS;
import static dev.santiagosantofimio.minidb.sql.TokenType.RIGHT_PAREN;
import static dev.santiagosantofimio.minidb.sql.TokenType.SELECT;
import static dev.santiagosantofimio.minidb.sql.TokenType.SEMICOLON;
import static dev.santiagosantofimio.minidb.sql.TokenType.SLASH;
import static dev.santiagosantofimio.minidb.sql.TokenType.STAR;
import static dev.santiagosantofimio.minidb.sql.TokenType.TABLE;
import static dev.santiagosantofimio.minidb.sql.TokenType.VALUES;
import static dev.santiagosantofimio.minidb.sql.TokenType.WHERE;

import dev.santiagosantofimio.minidb.SyntaxException;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.DataType.VarcharType;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.sql.Expression.Between;
import dev.santiagosantofimio.minidb.sql.Expression.Binary;
import dev.santiagosantofimio.minidb.sql.Expression.ColumnReference;
import dev.santiagosantofimio.minidb.sql.Expression.IsNull;
import dev.santiagosantofimio.minidb.sql.Expression.Literal;
import dev.santiagosantofimio.minidb.sql.Expression.Negate;
import dev.santiagosantofimio.minidb.sql.Expression.Not;
import dev.santiagosantofimio.minidb.sql.Statement.AllColumns;
import dev.santiagosantofimio.minidb.sql.Statement.ColumnDefinition;
import dev.santiagosantofimio.minidb.sql.Statement.CreateTable;
import dev.santiagosantofimio.minidb.sql.Statement.DropTable;
import dev.santiagosantofimio.minidb.sql.Statement.Identifier;
import dev.santiagosantofimio.minidb.sql.Statement.Insert;
import dev.santiagosantofimio.minidb.sql.Statement.Select;
import dev.santiagosantofimio.minidb.sql.Statement.SelectExpression;
import dev.santiagosantofimio.minidb.sql.Statement.SelectItem;
import dev.santiagosantofimio.minidb.sql.Statement.TableReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Parser {

    private final List<Token> tokens;
    private int current;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static List<Statement> parse(String sql) {
        return new Parser(Lexer.tokenize(sql)).script();
    }

    public static Expression parseExpression(String text) {
        Parser parser = new Parser(Lexer.tokenize(text));
        Expression expression = parser.expression();
        parser.expect(END);
        return expression;
    }

    private List<Statement> script() {
        List<Statement> statements = new ArrayList<>();
        while (!check(END)) {
            if (match(SEMICOLON)) {
                continue;
            }
            statements.add(statement());
            if (!check(END)) {
                expect(SEMICOLON);
            }
        }
        return statements;
    }

    private Statement statement() {
        Token token = peek();
        return switch (token.type()) {
            case SELECT -> select();
            case INSERT -> insert();
            case CREATE -> createTable();
            case DROP -> dropTable();
            default -> throw error(token,
                    "expected SELECT, INSERT, CREATE TABLE or DROP TABLE but found " + token.describe());
        };
    }

    private Statement createTable() {
        Token create = expect(CREATE);
        expect(TABLE);
        Identifier table = identifier("a table name");
        expect(LEFT_PAREN);
        List<ColumnDefinition> columns = new ArrayList<>();
        do {
            columns.add(columnDefinition());
        } while (match(COMMA));
        expect(RIGHT_PAREN);
        return new CreateTable(table, columns, create.position());
    }

    private ColumnDefinition columnDefinition() {
        Identifier name = identifier("a column name");
        DataType type = dataType();
        boolean nullable = true;
        if (match(NOT)) {
            expect(NULL);
            nullable = false;
        }
        return new ColumnDefinition(name, type, nullable);
    }

    private DataType dataType() {
        Token token = advance();
        return switch (token.type()) {
            case INT -> DataType.INT;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> DataType.BOOLEAN;
            case VARCHAR -> varcharLength();
            default -> throw error(token,
                    "expected a column type (INT, DOUBLE, BOOLEAN or VARCHAR(n)) but found " + token.describe());
        };
    }

    private DataType varcharLength() {
        expect(LEFT_PAREN);
        Token length = expect(INTEGER);
        int maxLength = parseIntOrZero(length.text());
        if (maxLength < 1 || maxLength > VarcharType.MAX_LENGTH) {
            throw error(length, "VARCHAR length must be between 1 and " + VarcharType.MAX_LENGTH);
        }
        expect(RIGHT_PAREN);
        return DataType.varchar(maxLength);
    }

    private Statement dropTable() {
        Token drop = expect(DROP);
        expect(TABLE);
        return new DropTable(identifier("a table name"), drop.position());
    }

    private Statement insert() {
        Token insert = expect(INSERT);
        expect(INTO);
        Identifier table = identifier("a table name");
        Optional<List<Identifier>> columns = Optional.empty();
        if (match(LEFT_PAREN)) {
            List<Identifier> names = new ArrayList<>();
            do {
                names.add(identifier("a column name"));
            } while (match(COMMA));
            expect(RIGHT_PAREN);
            columns = Optional.of(names);
        }
        expect(VALUES);
        List<List<Expression>> rows = new ArrayList<>();
        do {
            rows.add(valueRow());
        } while (match(COMMA));
        return new Insert(table, columns, rows, insert.position());
    }

    private List<Expression> valueRow() {
        expect(LEFT_PAREN);
        List<Expression> values = new ArrayList<>();
        do {
            values.add(expression());
        } while (match(COMMA));
        expect(RIGHT_PAREN);
        return values;
    }

    private Statement select() {
        Token select = expect(SELECT);
        List<SelectItem> items = new ArrayList<>();
        do {
            items.add(selectItem());
        } while (match(COMMA));
        expect(FROM);
        Identifier table = identifier("a table name");
        TableReference from = new TableReference(table, alias());
        Optional<Expression> where = match(WHERE) ? Optional.of(expression()) : Optional.empty();
        return new Select(items, from, where, select.position());
    }

    private SelectItem selectItem() {
        if (check(STAR)) {
            return new AllColumns(advance().position());
        }
        Expression expression = expression();
        Optional<Identifier> alias = match(AS) ? Optional.of(identifier("an alias")) : Optional.empty();
        return new SelectExpression(expression, alias);
    }

    private Optional<Identifier> alias() {
        if (match(AS) || check(IDENTIFIER)) {
            return Optional.of(identifier("an alias"));
        }
        return Optional.empty();
    }

    private Expression expression() {
        return or();
    }

    private Expression or() {
        Expression left = and();
        while (check(OR)) {
            Token operator = advance();
            left = new Binary(BinaryOperator.OR, left, and(), operator.position());
        }
        return left;
    }

    private Expression and() {
        Expression left = not();
        while (check(AND)) {
            Token operator = advance();
            left = new Binary(BinaryOperator.AND, left, not(), operator.position());
        }
        return left;
    }

    private Expression not() {
        if (check(NOT)) {
            Token not = advance();
            return new Not(not(), not.position());
        }
        return predicate();
    }

    private Expression predicate() {
        Expression left = additive();
        Token token = peek();
        Optional<BinaryOperator> comparison = comparison(token.type());
        if (comparison.isPresent()) {
            advance();
            return new Binary(comparison.get(), left, additive(), token.position());
        }
        if (token.type() == IS) {
            advance();
            boolean negated = match(NOT);
            expect(NULL);
            return new IsNull(left, negated, token.position());
        }
        if (token.type() == BETWEEN || (token.type() == NOT && peekNext().type() == BETWEEN)) {
            boolean negated = match(NOT);
            expect(BETWEEN);
            Expression low = additive();
            expect(AND);
            Expression high = additive();
            return new Between(left, low, high, negated, token.position());
        }
        return left;
    }

    private static Optional<BinaryOperator> comparison(TokenType type) {
        return Optional.ofNullable(switch (type) {
            case EQUALS -> BinaryOperator.EQUAL;
            case NOT_EQUALS -> BinaryOperator.NOT_EQUAL;
            case LESS -> BinaryOperator.LESS;
            case LESS_OR_EQUAL -> BinaryOperator.LESS_OR_EQUAL;
            case GREATER -> BinaryOperator.GREATER;
            case GREATER_OR_EQUAL -> BinaryOperator.GREATER_OR_EQUAL;
            default -> null;
        });
    }

    private Expression additive() {
        Expression left = multiplicative();
        while (check(PLUS) || check(MINUS)) {
            Token operator = advance();
            BinaryOperator op = operator.type() == PLUS ? BinaryOperator.ADD : BinaryOperator.SUBTRACT;
            left = new Binary(op, left, multiplicative(), operator.position());
        }
        return left;
    }

    private Expression multiplicative() {
        Expression left = unary();
        while (check(STAR) || check(SLASH)) {
            Token operator = advance();
            BinaryOperator op = operator.type() == STAR ? BinaryOperator.MULTIPLY : BinaryOperator.DIVIDE;
            left = new Binary(op, left, unary(), operator.position());
        }
        return left;
    }

    private Expression unary() {
        if (!check(MINUS)) {
            return primary();
        }
        Token minus = advance();
        if (check(INTEGER)) {
            Token number = advance();
            return new Literal(Value.of(parseInt("-" + number.text(), number)), minus.position());
        }
        if (check(DECIMAL)) {
            return new Literal(Value.of(-parseDouble(advance())), minus.position());
        }
        return new Negate(unary(), minus.position());
    }

    private Expression primary() {
        Token token = advance();
        return switch (token.type()) {
            case INTEGER -> new Literal(Value.of(parseInt(token.text(), token)), token.position());
            case DECIMAL -> new Literal(Value.of(parseDouble(token)), token.position());
            case STRING -> new Literal(Value.of(token.text()), token.position());
            case TRUE -> new Literal(Value.TRUE, token.position());
            case FALSE -> new Literal(Value.FALSE, token.position());
            case NULL -> new Literal(Value.NULL, token.position());
            case IDENTIFIER -> columnReference(token);
            case LEFT_PAREN -> {
                Expression inner = expression();
                expect(RIGHT_PAREN);
                yield inner;
            }
            default -> throw error(token, "expected an expression but found " + token.describe());
        };
    }

    private Expression columnReference(Token first) {
        if (match(DOT)) {
            Identifier column = identifier("a column name");
            return new ColumnReference(Optional.of(first.text()), column.name(), first.position());
        }
        return new ColumnReference(Optional.empty(), first.text(), first.position());
    }

    private static int parseInt(String text, Token token) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(token, text + " is out of range for INT");
        }
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(Token token) {
        double value = Double.parseDouble(token.text());
        if (Double.isInfinite(value)) {
            throw error(token, token.text() + " is out of range for DOUBLE");
        }
        return value;
    }

    private Identifier identifier(String expected) {
        Token token = peek();
        if (token.type() != IDENTIFIER) {
            throw error(token, "expected " + expected + " but found " + token.describe());
        }
        advance();
        return new Identifier(token.text(), token.position());
    }

    private Token expect(TokenType type) {
        Token token = peek();
        if (token.type() != type) {
            throw error(token, "expected " + type.description() + " but found " + token.describe());
        }
        return advance();
    }

    private boolean match(TokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token peekNext() {
        return tokens.get(Math.min(current + 1, tokens.size() - 1));
    }

    private Token advance() {
        Token token = tokens.get(current);
        if (token.type() != END) {
            current++;
        }
        return token;
    }

    private static SyntaxException error(Token token, String message) {
        return new SyntaxException(message, token.position());
    }
}
