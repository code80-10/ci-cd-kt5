import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {
    private static final String DB_URL = "jdbc:sqlite:" + Path.of("calculator.db").toAbsolutePath();
    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    @RegisterExtension
    static final TestWatcher WATCHER = new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
            PASSED.incrementAndGet();
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            FAILED.incrementAndGet();
        }
    };

    @BeforeAll
    static void prepareDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS test_data (id INTEGER PRIMARY KEY, operation TEXT NOT NULL, a REAL NOT NULL, b REAL NOT NULL, expected REAL NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS test_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, run_at TEXT NOT NULL, total INTEGER NOT NULL, passed INTEGER NOT NULL, failed INTEGER NOT NULL)");
            statement.executeUpdate("DELETE FROM test_data");
            statement.executeUpdate("INSERT INTO test_data VALUES " +
                    "(1, 'add', 2, 3, 5)," +
                    "(2, 'add', -4, 6, 2)," +
                    "(3, 'subtract', 10, 4, 6)," +
                    "(4, 'subtract', 3, 8, -5)," +
                    "(5, 'multiply', 4, 5, 20)," +
                    "(6, 'multiply', -3, 7, -21)," +
                    "(7, 'divide', 20, 4, 5)," +
                    "(8, 'divide', 7, 2, 3.5)");
            if ("wrong".equals(System.getProperty("dbMode"))) {
                statement.executeUpdate("UPDATE test_data SET expected = 6 WHERE id = 1");
            }
        }
    }

    static Stream<Arguments> testData() throws SQLException {
        List<Arguments> data = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT operation, a, b, expected FROM test_data ORDER BY id")) {
            while (resultSet.next()) {
                data.add(Arguments.of(
                        resultSet.getString("operation"),
                        resultSet.getDouble("a"),
                        resultSet.getDouble("b"),
                        resultSet.getDouble("expected")
                ));
            }
        }
        return data.stream();
    }

    @ParameterizedTest(name = "{0}({1}, {2}) = {3}")
    @MethodSource("testData")
    void calculatesFromDatabase(String operation, double a, double b, double expected) {
        Calculator calculator = new Calculator();
        double actual = switch (operation) {
            case "add" -> calculator.add(a, b);
            case "subtract" -> calculator.subtract(a, b);
            case "multiply" -> calculator.multiply(a, b);
            case "divide" -> calculator.divide(a, b);
            default -> throw new IllegalArgumentException("Unknown operation");
        };
        assertEquals(expected, actual, 0.000001);
    }

    @AfterAll
    static void saveRunResult() throws SQLException {
        int passed = PASSED.get();
        int failed = FAILED.get();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement("INSERT INTO test_runs(run_at, total, passed, failed) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, LocalDateTime.now().toString());
            statement.setInt(2, passed + failed);
            statement.setInt(3, passed);
            statement.setInt(4, failed);
            statement.executeUpdate();
        }
    }
}
