package dev.santiagosantofimio.minidb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.SyntaxException;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.sql.Expression.Binary;
import dev.santiagosantofimio.minidb.sql.Expression.Literal;
import dev.santiagosantofimio.minidb.sql.Statement.AllColumns;
import dev.santiagosantofimio.minidb.sql.Statement.CreateTable;
import dev.santiagosantofimio.minidb.sql.Statement.DropTable;
import dev.santiagosantofimio.minidb.sql.Statement.Insert;
import dev.santiagosantofimio.minidb.sql.Statement.Select;
import dev.santiagosantofimio.minidb.sql.Statement.SelectExpression;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParserTest {

    private static Statement single(String sql) {
        List<Statement> statements = Parser.parse(sql);
        assertEquals(1, statements.size());
        return statements.get(0);
    }

    private static SyntaxException syntaxError(String sql) {
        return assertThrows(SyntaxException.class, () -> Parser.parse(sql));
    }

    private static String reprint(String expression) {
        return SqlPrinter.print(Parser.parseExpression(expression));
    }

    @Test
    void parsesCreateTable() {
        CreateTable create = (CreateTable) single(
                "CREATE TABLE students (id INT NOT NULL, name VARCHAR(40), gpa DOUBLE, active BOOLEAN)");

        assertEquals("students", create.table().name());
        assertEquals(4, create.columns().size());
        assertEquals(DataType.INT, create.columns().get(0).type());
        assertFalse(create.columns().get(0).nullable());
        assertEquals(DataType.varchar(40), create.columns().get(1).type());
        assertTrue(create.columns().get(1).nullable());
    }

    @Test
    void parsesDropTable() {
        assertEquals("students", ((DropTable) single("DROP TABLE students;")).table().name());
    }

    @Test
    void parsesInsertWithColumnsAndSeveralRows() {
        Insert insert = (Insert) single("INSERT INTO students (id, name) VALUES (1, 'Ana'), (2, NULL)");

        assertEquals(List.of("id", "name"), insert.columns().orElseThrow().stream()
                .map(Statement.Identifier::name).toList());
        assertEquals(2, insert.rows().size());
        assertEquals(new Literal(Value.of("Ana"), new SourcePosition(1, 44)), insert.rows().get(0).get(1));
        assertEquals(Value.NULL, ((Literal) insert.rows().get(1).get(1)).value());
    }

    @Test
    void parsesInsertWithoutColumns() {
        assertTrue(((Insert) single("INSERT INTO t VALUES (1)")).columns().isEmpty());
    }

    @Test
    void parsesSelectWithAliasesAndWhere() {
        Select select = (Select) single("SELECT s.name AS student, gpa * 10 AS score FROM students s WHERE gpa > 3.5");

        assertEquals("s", select.from().effectiveName());
        assertEquals("student", ((SelectExpression) select.items().get(0)).alias().orElseThrow().name());
        assertEquals("score", ((SelectExpression) select.items().get(1)).alias().orElseThrow().name());
        assertEquals("gpa > 3.5", SqlPrinter.print(select.where().orElseThrow()));
    }

    @Test
    void parsesSelectStar() {
        Select select = (Select) single("SELECT * FROM students");

        assertInstanceOf(AllColumns.class, select.items().get(0));
        assertTrue(select.where().isEmpty());
        assertEquals("students", select.from().effectiveName());
    }

    @Test
    void parsesSeveralStatementsAndIgnoresEmptyOnes() {
        assertEquals(3, Parser.parse(";SELECT * FROM a;; SELECT * FROM b; DROP TABLE c;").size());
        assertEquals(0, Parser.parse("  ;  ").size());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "1 + 2 * 3              | 1 + 2 * 3",
        "(1 + 2) * 3            | (1 + 2) * 3",
        "10 - (4 - 3)           | 10 - (4 - 3)",
        "10 - 4 - 3             | 10 - 4 - 3",
        "a OR b AND c           | a OR b AND c",
        "(a OR b) AND c         | (a OR b) AND c",
        "NOT a = 1              | NOT a = 1",
        "NOT (a AND b)          | NOT (a AND b)",
        "-x * 2                 | -x * 2",
        "-(x + 1)               | -(x + 1)",
        "3 - -5                 | 3 - -5",
        "age BETWEEN 18 AND 30  | age BETWEEN 18 AND 30",
        "age not between 1 and 2| age NOT BETWEEN 1 AND 2",
        "name IS NOT NULL       | name IS NOT NULL",
        "s.gpa >= 3.5           | s.gpa >= 3.5",
        "'it''s'                | 'it''s'",
        "x <> 1 AND y != 2      | x <> 1 AND y <> 2",
        "true OR false          | TRUE OR FALSE"
    })
    void printsExpressionsWithMinimalParentheses(String source, String expected) {
        assertEquals(expected, reprint(source));
        assertEquals(Parser.parseExpression(expected), Parser.parseExpression(reprint(expected)));
    }

    @Test
    void multiplicationBindsTighterThanAddition() {
        Binary sum = (Binary) Parser.parseExpression("1 + 2 * 3");

        assertEquals(BinaryOperator.ADD, sum.operator());
        assertEquals(BinaryOperator.MULTIPLY, ((Binary) sum.right()).operator());
    }

    @Test
    void foldsMinusIntoNumericLiterals() {
        assertEquals(Value.of(Integer.MIN_VALUE), ((Literal) Parser.parseExpression("-2147483648")).value());
        assertEquals(Value.of(-2.5), ((Literal) Parser.parseExpression("-2.5")).value());
    }

    @Test
    void reportsWhereTheParserGotStuck() {
        SyntaxException error = syntaxError("SELECT name\nFORM students;");

        assertEquals(new SourcePosition(2, 1), error.position().orElseThrow());
        assertEquals("expected FROM but found 'form'", error.getMessage());
    }

    @Test
    void reportsMissingTokensAtTheEnd() {
        SyntaxException error = syntaxError("SELECT * FROM");

        assertEquals("expected a table name but found the end of the input", error.getMessage());
        assertEquals(new SourcePosition(1, 14), error.position().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELEC * FROM t",
        "SELECT FROM t",
        "SELECT * FROM t WHERE",
        "SELECT * FROM t ORDER BY x",
        "SELECT * FROM t WHERE a = b = c",
        "CREATE TABLE t ()",
        "CREATE TABLE t (x TEXT)",
        "CREATE TABLE t (x VARCHAR(0))",
        "CREATE TABLE t (x VARCHAR(4001))",
        "INSERT INTO t VALUES",
        "INSERT INTO t VALUES (1,)",
        "DROP students",
        "SELECT 2147483648 FROM t",
        "SELECT * FROM select"
    })
    void rejectsInvalidSql(String sql) {
        assertTrue(syntaxError(sql).position().isPresent());
    }

    @Test
    void keywordsCannotBeUsedAsNames() {
        assertEquals("expected a table name but found the keyword TABLE", syntaxError("DROP TABLE table").getMessage());
    }
}
