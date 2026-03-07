package sqlancer.materialize.oracle;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.materialize.MaterializeGlobalState;
import sqlancer.materialize.MaterializeSchema.MaterializeColumn;
import sqlancer.materialize.MaterializeSchema.MaterializeDataType;
import sqlancer.materialize.MaterializeSchema.MaterializeTable;
import sqlancer.materialize.MaterializeSchema.MaterializeTables;
import sqlancer.materialize.MaterializeVisitor;
import sqlancer.materialize.ast.MaterializeColumnValue;
import sqlancer.materialize.ast.MaterializeExpression;
import sqlancer.materialize.ast.MaterializeJoin;
import sqlancer.materialize.ast.MaterializeJoin.MaterializeJoinType;
import sqlancer.materialize.ast.MaterializeSelect;
import sqlancer.materialize.ast.MaterializeSelect.MaterializeFromTable;
import sqlancer.materialize.ast.MaterializeSelect.SelectType;
import sqlancer.materialize.gen.MaterializeCommon;
import sqlancer.materialize.gen.MaterializeExpressionGenerator;
import sqlancer.materialize.gen.MaterializeSetGenerator;

public class MaterializeDQPOracle implements TestOracle<MaterializeGlobalState> {

    private static final int MZ_SYSTEM_PORT = 6877;
    private static final String MZ_SYSTEM_USER = "mz_system";
    private static final String MZ_SYSTEM_PASSWORD = "materialize";

    private final MaterializeGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private final Connection mzSystemConnection;
    private String lastQueryString;

    public MaterializeDQPOracle(MaterializeGlobalState globalState) throws SQLException {
        this.state = globalState;
        errors.addAll(MaterializeCommon.getCommonExpressionErrors());
        errors.addAll(MaterializeCommon.getCommonFetchErrors());
        errors.add("canceling statement due to statement timeout");
        this.mzSystemConnection = createMzSystemConnection(globalState);
    }

    private static String resolveHost(MaterializeGlobalState globalState) {
        String host = "localhost";
        String connectionURL = globalState.getDbmsSpecificOptions().connectionURL;
        try {
            String url = connectionURL.startsWith("jdbc:") ? connectionURL.substring(5) : connectionURL;
            URI uri = new URI(url);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
        } catch (URISyntaxException e) {
            // use default
        }
        if (globalState.getOptions().getHost() != null) {
            host = globalState.getOptions().getHost();
        }
        return host;
    }

    private static Connection createMzSystemConnection(MaterializeGlobalState globalState) throws SQLException {
        String host = resolveHost(globalState);
        String url = String.format("jdbc:postgresql://%s:%d/materialize", host, MZ_SYSTEM_PORT);
        return DriverManager.getConnection(url, MZ_SYSTEM_USER, MZ_SYSTEM_PASSWORD);
    }

    private void executeOnSystemConnection(String sql) throws SQLException {
        try (Statement s = mzSystemConnection.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * Reconnect the main test connection so it picks up updated system variable defaults.
     */
    private void reconnectState() throws SQLException {
        String host = resolveHost(state);
        int port = state.getOptions().getPort();
        if (port == -1) {
            port = 6875;
        }
        String databaseName = state.getDatabaseName();
        String url = String.format("jdbc:postgresql://%s:%d/%s", host, port, databaseName);
        state.getConnection().close();
        Connection newCon = DriverManager.getConnection(url, "materialize", null);
        try (Statement s = newCon.createStatement()) {
            s.execute("SET CLUSTER = 'quickstart'");
            s.execute("SET transaction_isolation = 'SERIALIZABLE'");
            s.execute("SET auto_route_introspection_queries = false");
        }
        state.setConnection(new SQLConnection(newCon));
    }

    @Override
    public void check() throws Exception {
        MaterializeTables tables = state.getSchema().getRandomTableNonEmptyTables();
        List<MaterializeColumn> columns = tables.getColumns();
        MaterializeExpressionGenerator gen = new MaterializeExpressionGenerator(state).setColumns(columns);

        List<MaterializeExpression> fetchColumns = new ArrayList<>();
        fetchColumns.addAll(Randomly.nonEmptySubset(columns).stream().map(c -> new MaterializeColumnValue(c, null))
                .collect(Collectors.toList()));

        MaterializeSelect select = new MaterializeSelect();
        select.setFetchColumns(fetchColumns);
        select.setSelectType(SelectType.ALL);

        List<MaterializeTable> tableList = tables.getTables();
        List<MaterializeJoin> joins = getJoinStatements(tableList, gen);
        select.setJoinClauses(joins);

        List<MaterializeExpression> fromList = tableList.stream()
                .map(t -> new MaterializeFromTable(t, Randomly.getBoolean())).collect(Collectors.toList());
        select.setFromList(fromList);

        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression(MaterializeDataType.BOOLEAN));
        }
        if (Randomly.getBoolean()) {
            select.setGroupByExpressions(fetchColumns);
            if (Randomly.getBoolean()) {
                select.setHavingClause(gen.generateHavingClause());
            }
        }

        String originalQueryString = MaterializeVisitor.asString(select);
        lastQueryString = originalQueryString;
        List<String> originalResult = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors,
                state);

        List<String> optimizations = MaterializeSetGenerator.getAllOptimizerStatements();
        for (String optimization : optimizations) {
            executeOnSystemConnection(optimization);
            reconnectState();
            List<String> result;
            try {
                result = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);
            } catch (Exception e) {
                continue;
            }
            try {
                ComparatorHelper.assumeResultSetsAreEqual(originalResult, result, originalQueryString,
                        List.of(originalQueryString), state);
            } catch (AssertionError e) {
                String assertionMessage = String.format(
                        "The size of the result sets mismatch (%d and %d)!" + System.lineSeparator()
                                + "First query: \"%s\", whose cardinality is: %d" + System.lineSeparator()
                                + "Second query:\"%s\", whose cardinality is: %d",
                        originalResult.size(), result.size(), originalQueryString, originalResult.size(),
                        originalQueryString, result.size());
                assertionMessage += System.lineSeparator() + "The setting: " + optimization;
                throw new AssertionError(assertionMessage);
            }
        }
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

    private List<MaterializeJoin> getJoinStatements(List<MaterializeTable> tables,
            MaterializeExpressionGenerator gen) {
        List<MaterializeJoin> joinStatements = new ArrayList<>();
        for (int i = 1; i < tables.size(); i++) {
            MaterializeExpression joinClause = gen.generateExpression(MaterializeDataType.BOOLEAN);
            MaterializeTable table = Randomly.fromList(tables);
            tables.remove(table);
            MaterializeJoinType options = MaterializeJoinType.getRandom();
            MaterializeJoin j = new MaterializeJoin(new MaterializeFromTable(table, Randomly.getBoolean()), joinClause,
                    options);
            joinStatements.add(j);
        }
        return joinStatements;
    }
}
