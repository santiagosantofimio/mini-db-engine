package dev.santiagosantofimio.minidb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.santiagosantofimio.minidb.CatalogException;
import dev.santiagosantofimio.minidb.QueryException;
import dev.santiagosantofimio.minidb.SourcePosition;
import dev.santiagosantofimio.minidb.plan.Attribute;
import dev.santiagosantofimio.minidb.plan.ExpressionBinder;
import dev.santiagosantofimio.minidb.plan.ValueType;
import dev.santiagosantofimio.minidb.record.Tuple;
import dev.santiagosantofimio.minidb.record.Value;
import dev.santiagosantofimio.minidb.sql.Parser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EvaluatorTest {

    private static final List<Attribute> SCOPE = List.of(
            new Attribute(Optional.of("s"), "age", ValueType.INT),
            new Attribute(Optional.of("s"), "name", ValueType.VARCHAR),
            new Attribute(Optional.of("s"), "gpa", ValueType.DOUBLE),
            new Attribute(Optional.of("s"), "active", ValueType.BOOLEAN),
            new Attribute(Optional.of("s"), "unknown", ValueType.BOOLEAN));

    private static final Tuple ROW = Tuple.of(Value.of(21), Value.of("Ana"), Value.of(3.5), Value.TRUE, Value.NULL);

    private static Value eval(String expression) {
        return Evaluator.evaluate(new ExpressionBinder(SCOPE).bind(Parser.parseExpression(expression)), ROW);
    }

    private static Value literal(String text) {
        return switch (text) {
            case "TRUE" -> Value.TRUE;
            case "FALSE" -> Value.FALSE;
            case "NULL" -> Value.NULL;
            default -> text.contains(".") ? Value.of(Double.parseDouble(text)) : Value.of(Integer.parseInt(text));
        };
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "TRUE AND unknown    | NULL",
        "FALSE AND unknown   | FALSE",
        "unknown AND FALSE   | FALSE",
        "TRUE OR unknown     | TRUE",
        "FALSE OR unknown    | NULL",
        "NOT unknown         | NULL",
        "NOT active          | FALSE",
        "age = NULL          | NULL",
        "NULL = NULL         | NULL",
        "unknown IS NULL     | TRUE",
        "age IS NOT NULL     | TRUE",
        "age BETWEEN 18 AND 21 | TRUE",
        "age NOT BETWEEN 18 AND 21 | FALSE",
        "age BETWEEN NULL AND 30 | NULL",
        "age BETWEEN 22 AND NULL | FALSE"
    })
    void followsThreeValuedLogic(String expression, String expected) {
        assertEquals(literal(expected), eval(expression));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "age + 1      | 22",
        "7 / 2        | 3",
        "-7 / 2       | -3",
        "7.0 / 2      | 3.5",
        "age * gpa    | 73.5",
        "-age         | -21",
        "age - NULL   | NULL",
        "1 + 2 * 3    | 7",
        "(1 + 2) * 3  | 9"
    })
    void doesArithmetic(String expression, String expected) {
        assertEquals(literal(expected), eval(expression));
    }

    @Test
    void comparesAcrossNumericTypesAndStrings() {
        assertEquals(Value.TRUE, eval("age > 20.5"));
        assertEquals(Value.TRUE, eval("gpa = 3.5"));
        assertEquals(Value.TRUE, eval("name = 'Ana'"));
        assertEquals(Value.TRUE, eval("name < 'Bruno'"));
        assertEquals(Value.TRUE, eval("TRUE > FALSE"));
    }

    @Test
    void reportsEvaluationErrors() {
        assertEquals("division by zero", assertThrows(QueryException.class, () -> eval("age / 0")).getMessage());
        assertThrows(QueryException.class, () -> eval("gpa / 0"));
        assertThrows(QueryException.class, () -> eval("2147483647 + 1"));
        assertThrows(QueryException.class, () -> eval("-2147483648 / -1"));
        assertThrows(QueryException.class, () -> eval("-(-2147483648)"));
    }

    @Test
    void falseShortCircuitsAnd() {
        assertEquals(Value.FALSE, eval("FALSE AND age / 0 = 1"));
    }

    @Test
    void rejectsTypeErrorsWithThePositionOfTheOperator() {
        QueryException error = assertThrows(QueryException.class, () -> eval("age + name"));

        assertEquals(new SourcePosition(1, 5), error.position().orElseThrow());
        assertThrows(QueryException.class, () -> eval("name > 3"));
        assertThrows(QueryException.class, () -> eval("age AND active"));
        assertThrows(QueryException.class, () -> eval("NOT age"));
        assertThrows(QueryException.class, () -> eval("-name"));
    }

    @Test
    void resolvesQualifiedAndUnqualifiedColumns() {
        assertEquals(Value.of("Ana"), eval("s.name"));
        CatalogException unknown = assertThrows(CatalogException.class, () -> eval("1 + nme"));
        assertEquals("unknown column nme", unknown.getMessage());
        assertEquals(new SourcePosition(1, 5), unknown.position().orElseThrow());
        assertThrows(CatalogException.class, () -> eval("t.name"));
    }

    @Test
    void reportsAmbiguousColumns() {
        List<Attribute> joined = List.of(
                new Attribute(Optional.of("s"), "id", ValueType.INT),
                new Attribute(Optional.of("e"), "id", ValueType.INT));
        ExpressionBinder binder = new ExpressionBinder(joined);

        assertThrows(CatalogException.class, () -> binder.bind(Parser.parseExpression("id = 1")));
        assertEquals(ValueType.BOOLEAN, binder.bind(Parser.parseExpression("e.id = 1")).type());
    }
}
