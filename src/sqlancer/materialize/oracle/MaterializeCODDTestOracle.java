package sqlancer.materialize.oracle;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Main;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.CODDTestBase;
import sqlancer.common.oracle.TestOracle;
import sqlancer.materialize.MaterializeGlobalState;
import sqlancer.materialize.MaterializeSchema;
import sqlancer.materialize.MaterializeSchema.MaterializeColumn;
import sqlancer.materialize.MaterializeSchema.MaterializeDataType;
import sqlancer.materialize.MaterializeSchema.MaterializeTable;
import sqlancer.materialize.MaterializeSchema.MaterializeTables;
import sqlancer.materialize.MaterializeVisitor;
import sqlancer.materialize.ast.MaterializeAggregate;
import sqlancer.materialize.ast.MaterializeAggregate.MaterializeAggregateFunction;
import sqlancer.materialize.ast.MaterializeAlias;
import sqlancer.materialize.ast.MaterializeAllOperator;
import sqlancer.materialize.ast.MaterializeAnyOperator;
import sqlancer.materialize.ast.MaterializeBinaryComparisonOperation;
import sqlancer.materialize.ast.MaterializeBinaryComparisonOperation.MaterializeBinaryComparisonOperator;
import sqlancer.materialize.ast.MaterializeBinaryLogicalOperation;
import sqlancer.materialize.ast.MaterializeBinaryLogicalOperation.BinaryLogicalOperator;
import sqlancer.materialize.ast.MaterializeColumnValue;
import sqlancer.materialize.ast.MaterializeConstant;
import sqlancer.materialize.ast.MaterializeExists;
import sqlancer.materialize.ast.MaterializeExpression;
import sqlancer.materialize.ast.MaterializeExpressionBag;
import sqlancer.materialize.ast.MaterializeInOperation;
import sqlancer.materialize.ast.MaterializeJoin;
import sqlancer.materialize.ast.MaterializeJoin.MaterializeJoinType;
import sqlancer.materialize.ast.MaterializeOrderByTerm;
import sqlancer.materialize.ast.MaterializeOrderByTerm.MaterializeOrder;
import sqlancer.materialize.ast.MaterializeResultMap;
import sqlancer.materialize.ast.MaterializeSelect;
import sqlancer.materialize.ast.MaterializeSelect.MaterializeFromTable;
import sqlancer.materialize.ast.MaterializeValues;
import sqlancer.materialize.gen.MaterializeCommon;
import sqlancer.materialize.gen.MaterializeExpressionGenerator;


public class MaterializeCODDTestOracle extends CODDTestBase<MaterializeGlobalState> implements TestOracle<MaterializeGlobalState> {

    private final MaterializeSchema s;
    private MaterializeExpressionGenerator gen;
    private Reproducer<MaterializeGlobalState> reproducer;

    private String tempTableName = "temp_table";

    private MaterializeExpression foldedExpr;
    private MaterializeExpression constantResOfFoldedExpr;

    private List<MaterializeTable> tablesFromOuterContext = new ArrayList<>();
    private List<MaterializeJoin> joinsInExpr = null;

    Map<String, List<MaterializeConstant>> auxiliaryQueryResult = new HashMap<>();
    Map<String, List<MaterializeConstant>> selectResult = new HashMap<>();

    Boolean useSubqueryAsFoldedExpr;
    Boolean useCorrelatedSubqueryAsFoldedExpr;

    MaterializeDataType foldedExpressionReturnType = null;

    public MaterializeCODDTestOracle(MaterializeGlobalState globalState) {
        super(globalState);
        this.s = globalState.getSchema();
        MaterializeCommon.addCommonExpressionErrors(errors);
        MaterializeCommon.addCommonFetchErrors(errors);
        errors.add("canceling statement due to statement timeout");
        errors.add("could not determine which collation to use");
        errors.add("does not exist");
        errors.add("could not decorrelate subquery");
        errors.add("subquery uses ungrouped column");
        errors.add("aggregate functions are not allowed in");
        errors.add("must appear in the GROUP BY clause or be used in an aggregate function");
        errors.add("is only defined for finite arguments");
        errors.add("result exceeds max size of");
        errors.add("VALUES types");
        errors.add("cannot be matched");
        errors.add("invalid input syntax");
        errors.add("value out of range");
        errors.add("operator does not exist");
        errors.add("number of columns");
        errors.add("Expected exactly one");
        errors.add("more than one record");
        errors.add("HAVING argument must be type boolean");
        errors.add("cannot be cast");
    }

    @Override
    public void check() throws Exception {
        reproducer = null;

        joinsInExpr = null;
        tablesFromOuterContext.clear();

        useSubqueryAsFoldedExpr = Randomly.getBoolean();
        useCorrelatedSubqueryAsFoldedExpr = Randomly.getBoolean();

        MaterializeSelect auxiliaryQuery = null;

        if (useSubqueryAsFoldedExpr) {
            if (useCorrelatedSubqueryAsFoldedExpr) {
                auxiliaryQuery = genSelectWithCorrelatedSubquery();
                auxiliaryQueryString = MaterializeVisitor.asString(auxiliaryQuery);
                auxiliaryQueryResult.putAll(selectResult);
            } else {
                auxiliaryQuery = genSelectExpression(null, null, null);
                auxiliaryQueryString = MaterializeVisitor.asString(auxiliaryQuery);
                auxiliaryQueryResult = getQueryResult(auxiliaryQueryString, state);
            }
        } else {
            auxiliaryQuery = genSimpleSelect();
            auxiliaryQueryString = MaterializeVisitor.asString(auxiliaryQuery);
            auxiliaryQueryResult.putAll(selectResult);
        }

        MaterializeSelect originalQuery = null;

        Map<String, List<MaterializeConstant>> foldedResult = new HashMap<>();
        Map<String, List<MaterializeConstant>> originalResult = new HashMap<>();

        // dependent expression
        if (!useSubqueryAsFoldedExpr || (useSubqueryAsFoldedExpr && useCorrelatedSubqueryAsFoldedExpr)) {
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(this.foldedExpr);
            originalQuery = this.genSelectExpression(null, specificCondition, foldedExpressionReturnType);
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            specificCondition.updateInnerExpr(this.constantResOfFoldedExpr);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // independent expression
        // empty result, put the inner query in (NOT) EXIST
        else if (auxiliaryQueryResult.size() == 0 || auxiliaryQueryResult.get(auxiliaryQueryResult.keySet().iterator().next()).size() == 0) {
            boolean isNegated = Randomly.getBoolean();

            // original query
            MaterializeExists existExpr = new MaterializeExists(auxiliaryQuery, isNegated);
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(existExpr);

            originalQuery = this.genSelectExpression(null, specificCondition, foldedExpressionReturnType);
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            MaterializeExpression equivalentExpr = MaterializeConstant.createBooleanConstant(isNegated);
            specificCondition.updateInnerExpr(equivalentExpr);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // Scalar Subquery: 1 column and 1 row, consider the inner query as a constant
        else if (auxiliaryQueryResult.size() == 1 && auxiliaryQueryResult.get(auxiliaryQueryResult.keySet().toArray()[0]).size() == 1 && Randomly.getBoolean()) {
            // original query
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(auxiliaryQuery);

            originalQuery = this.genSelectExpression(null, specificCondition, getColumnTypeFromSelect(auxiliaryQuery).get(0));
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            MaterializeExpression equivalentExpr = auxiliaryQueryResult.get(auxiliaryQueryResult.keySet().toArray()[0]).get(0);
            specificCondition.updateInnerExpr(equivalentExpr);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // one column - IN
        else if (auxiliaryQueryResult.size() == 1 && Randomly.getBoolean()) {
            // original query
            List<MaterializeColumn> columns = s.getRandomTableNonEmptyTables().getColumns();
            MaterializeColumnValue selectedColumn = MaterializeColumnValue.create(Randomly.fromList(columns), null);
            MaterializeTable selectedTable = selectedColumn.getColumn().getTable();
            MaterializeFromTable selectedTableRef = new MaterializeFromTable(selectedTable, false);
            MaterializeExpressionBag tableBag = new MaterializeExpressionBag(selectedTableRef);

            MaterializeInOperation optInOperation = new MaterializeInOperation(selectedColumn, Arrays.asList(auxiliaryQuery), true);
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(optInOperation);

            originalQuery = this.genSelectExpression(tableBag, specificCondition, null);
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            MaterializeColumn tempColumn = new MaterializeColumn("c0", getColumnTypeFromSelect(auxiliaryQuery).get(0));
            LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> value = new LinkedHashMap<>();
            value.put(tempColumn, auxiliaryQueryResult.values().iterator().next());
            MaterializeValues refValues = new MaterializeValues(value);
            MaterializeInOperation refInOperation = new MaterializeInOperation(selectedColumn, Arrays.asList(refValues), true);
            specificCondition.updateInnerExpr(refInOperation);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // ALL
        else if (auxiliaryQueryResult.size() == 1 && Randomly.getBoolean()) {
            // original query
            List<MaterializeColumn> columns = s.getRandomTableNonEmptyTables().getColumns();
            MaterializeColumnValue selectedColumn = MaterializeColumnValue.create(Randomly.fromList(columns), null);
            MaterializeTable selectedTable = selectedColumn.getColumn().getTable();
            MaterializeFromTable selectedTableRef = new MaterializeFromTable(selectedTable, false);
            MaterializeExpressionBag tableBag = new MaterializeExpressionBag(selectedTableRef);

            MaterializeExpression allOptLeft = genCondition(gen, null, null);
            MaterializeBinaryComparisonOperator allOperator = MaterializeBinaryComparisonOperator.getRandom();

            MaterializeAllOperator optAllOperation = new MaterializeAllOperator(allOptLeft, auxiliaryQuery, allOperator);
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(optAllOperation);
            originalQuery = this.genSelectExpression(tableBag, specificCondition, null);
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            MaterializeColumn tempColumn = new MaterializeColumn("c0", getColumnTypeFromSelect(auxiliaryQuery).get(0));
            LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> value = new LinkedHashMap<>();
            value.put(tempColumn, auxiliaryQueryResult.values().iterator().next());
            MaterializeValues refValues = new MaterializeValues(value);
            MaterializeAllOperator refAllOperation = new MaterializeAllOperator(allOptLeft, refValues, allOperator);
            specificCondition.updateInnerExpr(refAllOperation);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // ANY
        else if (auxiliaryQueryResult.size() == 1 && Randomly.getBoolean()) {
            // original query
            List<MaterializeColumn> columns = s.getRandomTableNonEmptyTables().getColumns();
            MaterializeColumnValue selectedColumn = MaterializeColumnValue.create(Randomly.fromList(columns), null);
            MaterializeTable selectedTable = selectedColumn.getColumn().getTable();
            MaterializeFromTable selectedTableRef = new MaterializeFromTable(selectedTable, false);
            MaterializeExpressionBag tableBag = new MaterializeExpressionBag(selectedTableRef);

            MaterializeExpression anyOptLeft = genCondition(gen, null, null);
            MaterializeBinaryComparisonOperator anyOperator = MaterializeBinaryComparisonOperator.getRandom();

            MaterializeAnyOperator optAnyOperation = new MaterializeAnyOperator(anyOptLeft, auxiliaryQuery, anyOperator);
            MaterializeExpressionBag specificCondition = new MaterializeExpressionBag(optAnyOperation);
            originalQuery = this.genSelectExpression(tableBag, specificCondition, null);
            originalQueryString = MaterializeVisitor.asString(originalQuery);
            originalResult = getQueryResult(originalQueryString, state);

            // folded query
            MaterializeColumn tempColumn = new MaterializeColumn("c0", getColumnTypeFromSelect(auxiliaryQuery).get(0));
            LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> value = new LinkedHashMap<>();
            value.put(tempColumn, auxiliaryQueryResult.values().iterator().next());
            MaterializeValues refValues = new MaterializeValues(value);
            MaterializeAnyOperator refAnyOperation = new MaterializeAnyOperator(anyOptLeft, refValues, anyOperator);
            specificCondition.updateInnerExpr(refAnyOperation);
            foldedQueryString = MaterializeVisitor.asString(originalQuery);
            foldedResult = getQueryResult(foldedQueryString, state);
        }
        // Row Subquery - use CREATE TABLE approach
        else {
            try {
                MaterializeTable temporaryTable = this.createTemporaryTable(auxiliaryQuery, this.tempTableName);
                MaterializeFromTable tempTableRef = new MaterializeFromTable(temporaryTable, false);
                MaterializeExpressionBag tempTableRefBag = new MaterializeExpressionBag(tempTableRef);

                originalQuery = genSelectExpression(tempTableRefBag, null, null);
                originalQueryString = MaterializeVisitor.asString(originalQuery);
                originalResult = getQueryResult(originalQueryString, state);

                // Insert data from auxiliary query
                String insertString = "INSERT INTO " + this.tempTableName + " " + MaterializeVisitor.asString(auxiliaryQuery);
                executeStatement(insertString);

                foldedQueryString = MaterializeVisitor.asString(originalQuery);
                foldedResult = getQueryResult(foldedQueryString, state);
            } finally {
                dropTemporaryTable(this.tempTableName);
            }
        }
        if (foldedResult == null || originalResult == null) {
            throw new IgnoreMeException();
        }
        if (foldedQueryString.equals(originalQueryString)) {
            throw new IgnoreMeException();
        }
        if (!compareResult(foldedResult, originalResult)) {
            reproducer = null;
            state.getState().getLocalState().log(auxiliaryQueryString + ";\n" + foldedQueryString + ";\n" + originalQueryString + ";");
            throw new AssertionError(auxiliaryQueryResult.toString() + " " + foldedResult.toString() + " " + originalResult.toString());
        }
    }

    private MaterializeSelect genSelectExpression(MaterializeExpressionBag tempTableRefBag, MaterializeExpression specificCondition, MaterializeDataType conditionType) {
        MaterializeTables randomTables = s.getRandomTableNonEmptyTables();
        if (!useSubqueryAsFoldedExpr || (useSubqueryAsFoldedExpr && useCorrelatedSubqueryAsFoldedExpr)) {
            for (MaterializeTable t : this.tablesFromOuterContext) {
                randomTables.addTable(t);
            }
            if (this.joinsInExpr != null) {
                for (MaterializeJoin j : this.joinsInExpr) {
                    MaterializeExpression leftExpr = j.getTableReference();
                    if (leftExpr instanceof MaterializeFromTable) {
                        randomTables.addTable(((MaterializeFromTable) leftExpr).getTable());
                    }
                }
            }
        }

        List<MaterializeColumn> columns = randomTables.getColumns();

        MaterializeTable tempTable = null;
        MaterializeFromTable tempTableRef = null;

        if (tempTableRefBag != null) {
            tempTableRef = (MaterializeFromTable) tempTableRefBag.getInnerExpr();
            tempTable = tempTableRef.getTable();
        }

        if (tempTable != null) {
            columns.addAll(tempTable.getColumns());
        }

        gen = new MaterializeExpressionGenerator(state).setColumns(columns);
        List<MaterializeTable> tables = randomTables.getTables();
        List<MaterializeExpression> tableRefs = tables.stream().map(t -> new MaterializeFromTable(t, Randomly.getBoolean())).collect(Collectors.toList());
        MaterializeSelect select = new MaterializeSelect();

        if (tempTableRefBag != null) {
            boolean isContained = false;
            for (MaterializeExpression tre : tableRefs) {
                MaterializeFromTable tr = (MaterializeFromTable) tre;
                if (tr.getTable().getName().equals(tempTable.getName())) {
                    isContained = true;
                    break;
                }
            }
            if (!isContained) {
                tableRefs.add(tempTableRefBag);
            }
        }

        if (!useSubqueryAsFoldedExpr || (useSubqueryAsFoldedExpr && useCorrelatedSubqueryAsFoldedExpr)) {
            if (this.joinsInExpr != null) {
                Iterator<MaterializeExpression> iterator = tableRefs.iterator();
                while (iterator.hasNext()) {
                    MaterializeExpression e = iterator.next();
                    if (e instanceof MaterializeFromTable) {
                        MaterializeFromTable tableRef = (MaterializeFromTable) e;
                        for (MaterializeJoin j : this.joinsInExpr) {
                            MaterializeExpression jTableRef = j.getTableReference();
                            if (jTableRef instanceof MaterializeFromTable) {
                                if (tableRef.getTable().equals(((MaterializeFromTable) jTableRef).getTable())) {
                                    iterator.remove();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<MaterializeJoin> joinExpressions = new ArrayList<>();
        if ((!useSubqueryAsFoldedExpr || (useSubqueryAsFoldedExpr && useCorrelatedSubqueryAsFoldedExpr))) {
            if (this.joinsInExpr != null) {
                joinExpressions.addAll(this.joinsInExpr);
                this.joinsInExpr = null;
            }
        } else if (Randomly.getBoolean()) {
            joinExpressions = getJoins(tableRefs, state);
        }

        select.setFromList(tableRefs);

        if (joinExpressions.size() > 0) {
            select.setJoinClauses(joinExpressions);
        }

        select.setWhereClause(genCondition(gen, specificCondition, conditionType));

        if (Randomly.getBooleanWithSmallProbability()) {
            select.setOrderByClauses(genOrderBys(specificCondition, conditionType));
        }

        if (Randomly.getBoolean()) {
            List<MaterializeColumn> selectedColumns = Randomly.nonEmptySubset(columns);
            List<MaterializeExpression> selectedAlias = new LinkedList<>();
            for (int i = 0; i < selectedColumns.size(); ++i) {
                MaterializeColumnValue originalName = MaterializeColumnValue.create(selectedColumns.get(i), null);
                MaterializeAlias columnAlias = new MaterializeAlias(originalName, "c" + String.valueOf(i));
                selectedAlias.add(columnAlias);
            }
            select.setFetchColumns(selectedAlias);
        } else {
            MaterializeColumn selectedColumn = Randomly.fromList(columns);
            MaterializeColumnValue aggr = MaterializeColumnValue.create(selectedColumn, null);
            List<MaterializeAggregateFunction> windowFunctionList = new ArrayList<>(MaterializeAggregateFunction.getAggregates(selectedColumn.getType()));
            // Exclude precision-problematic aggregates
            MaterializeDataType selType = selectedColumn.getType();
            if (selType == MaterializeDataType.FLOAT || selType == MaterializeDataType.REAL || selType == MaterializeDataType.DECIMAL) {
                windowFunctionList.remove(MaterializeAggregateFunction.AVG);
                windowFunctionList.remove(MaterializeAggregateFunction.SUM);
            }
            if (selType == MaterializeDataType.TEXT) {
                windowFunctionList.remove(MaterializeAggregateFunction.MAX);
                windowFunctionList.remove(MaterializeAggregateFunction.MIN);
            }
            if (windowFunctionList.isEmpty()) {
                windowFunctionList.add(MaterializeAggregateFunction.COUNT);
            }
            MaterializeAggregateFunction windowFunction = Randomly.fromList(windowFunctionList);
            MaterializeExpression originalName = new MaterializeAggregate(Arrays.asList(aggr), windowFunction);
            MaterializeAlias columnAlias = new MaterializeAlias(originalName, "c0");
            select.setFetchColumns(Arrays.asList(columnAlias));
            if (Randomly.getBoolean()) {
                select.setGroupByExpressions(genGroupBys(columns, Randomly.getBooleanWithRatherLowProbability() ? specificCondition : null, conditionType));
            }
            if (Randomly.getBoolean()) {
                MaterializeExpressionGenerator havingGen = new MaterializeExpressionGenerator(state).setColumns(columns);
                select.setHavingClause(genCondition(havingGen, (Randomly.getBooleanWithRatherLowProbability() && (!useSubqueryAsFoldedExpr || (useSubqueryAsFoldedExpr && !useCorrelatedSubqueryAsFoldedExpr))) ? specificCondition : null, conditionType));
            }
        }
        return select;
    }

    public MaterializeExpression genCondition(MaterializeExpressionGenerator generator, MaterializeExpression specificCondition, MaterializeDataType conditionType) {
        if (specificCondition == null) {
            conditionType = null;
        }
        MaterializeExpression randomWhereCondition = generator.generateExpression(conditionType == null ? MaterializeDataType.BOOLEAN : conditionType);
        MaterializeExpression whereCondition = null;
        if (specificCondition != null) {
            if (conditionType == null || conditionType == MaterializeDataType.BOOLEAN) {
                whereCondition = new MaterializeBinaryLogicalOperation(randomWhereCondition, specificCondition, BinaryLogicalOperator.getRandom());
            } else {
                whereCondition = new MaterializeBinaryComparisonOperation(randomWhereCondition, specificCondition, MaterializeBinaryComparisonOperator.getRandom());
            }
        } else {
            whereCondition = randomWhereCondition;
        }
        return whereCondition;
    }

    public List<MaterializeJoin> getJoins(List<MaterializeExpression> tableList, MaterializeGlobalState globalState) {
        List<MaterializeJoin> joinExpressions = new ArrayList<>();
        // Keep at least one table in the FROM list
        while (tableList.size() >= 3 && Randomly.getBoolean()) {
            MaterializeExpression leftExpr = tableList.remove(0);
            MaterializeExpression rightExpr = tableList.remove(0);

            MaterializeTable leftTable = null;
            MaterializeTable rightTable = null;

            if (leftExpr instanceof MaterializeFromTable) {
                leftTable = ((MaterializeFromTable) leftExpr).getTable();
            } else if (leftExpr instanceof MaterializeExpressionBag) {
                leftTable = ((MaterializeFromTable) ((MaterializeExpressionBag) leftExpr).getInnerExpr()).getTable();
            } else {
                throw new AssertionError();
            }

            if (rightExpr instanceof MaterializeFromTable) {
                rightTable = ((MaterializeFromTable) rightExpr).getTable();
            } else if (rightExpr instanceof MaterializeExpressionBag) {
                rightTable = ((MaterializeFromTable) ((MaterializeExpressionBag) rightExpr).getInnerExpr()).getTable();
            } else {
                throw new AssertionError();
            }

            List<MaterializeColumn> columns = new ArrayList<>(leftTable.getColumns());
            columns.addAll(rightTable.getColumns());
            MaterializeExpressionGenerator joinGen = new MaterializeExpressionGenerator(globalState).setColumns(columns);
            joinExpressions.add(new MaterializeJoin(rightExpr, joinGen.generateExpression(MaterializeDataType.BOOLEAN), MaterializeJoinType.getRandom()));
        }
        return joinExpressions;
    }

    public List<MaterializeExpression> genOrderBys(MaterializeExpression specificCondition, MaterializeDataType conditionType) {
        List<MaterializeExpression> orderingTerms = new ArrayList<>();
        int nr = 1;
        while (Randomly.getBooleanWithSmallProbability()) {
            nr++;
        }
        for (int i = 0; i < nr; i++) {
            MaterializeExpression expr = genCondition(gen, specificCondition, conditionType);
            if (Randomly.getBoolean()) {
                expr = new MaterializeOrderByTerm(expr, MaterializeOrder.getRandomOrder());
            }
            orderingTerms.add(expr);
        }
        return orderingTerms;
    }

    private List<MaterializeExpression> genGroupBys(List<MaterializeColumn> columns, MaterializeExpression specificCondition, MaterializeDataType conditionType) {
        MaterializeExpressionGenerator groupByGen = new MaterializeExpressionGenerator(state).setColumns(columns);
        int exprNum = Randomly.smallNumber() + 1;
        List<MaterializeExpression> newExpressions = new ArrayList<>();
        for (int i = 0; i < exprNum; ++i) {
            MaterializeExpression condition = genCondition(groupByGen, Randomly.getBooleanWithRatherLowProbability() ? specificCondition : null, conditionType);
            newExpressions.add(condition);
        }
        return newExpressions;
    }

    private Map<String, List<MaterializeConstant>> getQueryResult(String queryString, MaterializeGlobalState state) throws SQLException {
        Map<String, List<MaterializeConstant>> result = new LinkedHashMap<>();
        if (options.logEachSelect()) {
            logger.writeCurrent(queryString);
        }
        Statement stmt = null;
        try {
            stmt = this.con.createStatement();
            ResultSet rs = null;
            try {
                rs = stmt.executeQuery(queryString);
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                Map<Integer, String> idxNameMap = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    result.put("c" + String.valueOf(i - 1), new ArrayList<>());
                    idxNameMap.put(i, "c" + String.valueOf(i - 1));
                }

                int resultRows = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        try {
                            Object value = rs.getObject(i);
                            MaterializeConstant constant;
                            if (rs.wasNull() || value == null) {
                                constant = MaterializeConstant.createNullConstant();
                            } else if (value instanceof Boolean) {
                                constant = MaterializeConstant.createBooleanConstant((Boolean) value);
                            } else if (value instanceof Integer) {
                                constant = MaterializeConstant.createIntConstant(Long.valueOf((Integer) value));
                            } else if (value instanceof Short) {
                                constant = MaterializeConstant.createIntConstant(Long.valueOf((Short) value));
                            } else if (value instanceof Long) {
                                constant = MaterializeConstant.createIntConstant((Long) value);
                            } else if (value instanceof Double) {
                                constant = MaterializeConstant.createDoubleConstant((Double) value);
                            } else if (value instanceof Float) {
                                constant = MaterializeConstant.createFloatConstant((Float) value);
                            } else if (value instanceof BigDecimal) {
                                constant = MaterializeConstant.createDecimalConstant((BigDecimal) value);
                            } else if (value instanceof String) {
                                constant = MaterializeConstant.createTextConstant((String) value);
                            } else {
                                throw new IgnoreMeException();
                            }
                            List<MaterializeConstant> v = result.get(idxNameMap.get(i));
                            v.add(constant);
                        } catch (SQLException e) {
                            throw new IgnoreMeException();
                        }
                    }
                    ++resultRows;
                    if (resultRows > 100) {
                        throw new IgnoreMeException();
                    }
                }
                rs.close();
                Main.nrSuccessfulActions.addAndGet(1);
            } catch (SQLException e) {
                Main.nrUnsuccessfulActions.addAndGet(1);
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                } else {
                    state.getState().getLocalState().log(queryString);
                    throw new AssertionError(e.getMessage());
                }
            } finally {
                if (rs != null) {
                    rs.close();
                }
            }
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return result;
    }

    private MaterializeTable createTemporaryTable(MaterializeSelect select, String tableName) throws SQLException {
        List<MaterializeExpression> fetchColumns = select.getFetchColumns();
        int columnNumber = fetchColumns.size();
        Map<Integer, MaterializeDataType> idxTypeMap = getColumnTypeFromSelect(select);

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE " + tableName + " (");
        for (int i = 0; i < columnNumber; ++i) {
            String columnTypeName = "";
            if (idxTypeMap.get(i) != null) {
                columnTypeName = materializeDataTypeToSqlString(idxTypeMap.get(i));
            }
            sb.append("c" + String.valueOf(i) + " " + columnTypeName);
            if (i < columnNumber - 1) {
                sb.append(", ");
            }
        }
        sb.append(");");
        String createTableString = sb.toString();
        executeStatement(createTableString);

        List<MaterializeColumn> databaseColumns = new ArrayList<>();
        for (int i = 0; i < columnNumber; ++i) {
            String columnName = "c" + String.valueOf(i);
            MaterializeColumn column = new MaterializeColumn(columnName, idxTypeMap.get(i));
            databaseColumns.add(column);
        }
        MaterializeTable table = new MaterializeTable(tableName, databaseColumns, Collections.emptyList(),
                MaterializeTable.TableType.STANDARD, Collections.emptyList(), false, true);
        for (MaterializeColumn c : databaseColumns) {
            c.setTable(table);
        }

        return table;
    }

    private void executeStatement(String statement) throws SQLException {
        if (options.logEachSelect()) {
            logger.writeCurrent(statement);
        }
        Statement stmt = null;
        try {
            stmt = this.con.createStatement();
            try {
                stmt.execute(statement);
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                } else {
                    state.getState().getLocalState().log(statement);
                    throw new AssertionError(e.getMessage());
                }
            }
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }
    }

    private void dropTemporaryTable(String tableName) throws SQLException {
        String dropString = "DROP TABLE IF EXISTS " + tableName + ";";
        if (options.logEachSelect()) {
            logger.writeCurrent(dropString);
        }
        Statement stmt = null;
        try {
            stmt = this.con.createStatement();
            try {
                stmt.execute(dropString);
            } catch (SQLException e) {
                throw new IgnoreMeException();
            }
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }
    }

    private Map<Integer, MaterializeDataType> getColumnTypeFromSelect(MaterializeSelect select) {
        List<MaterializeExpression> fetchColumns = select.getFetchColumns();
        Map<Integer, MaterializeDataType> idxTypeMap = new HashMap<>();

        for (int i = 0; i < fetchColumns.size(); ++i) {
            MaterializeExpression column = fetchColumns.get(i);
            MaterializeDataType columnType = null;

            if (column instanceof MaterializeAlias) {
                MaterializeAlias alias = (MaterializeAlias) column;
                MaterializeExpression inner = alias.getExpression();
                if (inner instanceof MaterializeColumnValue) {
                    columnType = ((MaterializeColumnValue) inner).getColumn().getType();
                } else if (inner instanceof MaterializeAggregate) {
                    MaterializeAggregate aggr = (MaterializeAggregate) inner;
                    List<MaterializeExpression> aggrArgs = aggr.getArgs();
                    if (!aggrArgs.isEmpty() && aggrArgs.get(0) instanceof MaterializeColumnValue) {
                        columnType = ((MaterializeColumnValue) aggrArgs.get(0)).getColumn().getType();
                    }
                } else if (inner instanceof MaterializeSelect) {
                    MaterializeSelect sub = (MaterializeSelect) inner;
                    if (!sub.getFetchColumns().isEmpty()) {
                        MaterializeExpression subCol = sub.getFetchColumns().get(0);
                        if (subCol instanceof MaterializeColumnValue) {
                            columnType = ((MaterializeColumnValue) subCol).getColumn().getType();
                        } else if (subCol instanceof MaterializeAggregate) {
                            MaterializeAggregate subAggr = (MaterializeAggregate) subCol;
                            if (!subAggr.getArgs().isEmpty() && subAggr.getArgs().get(0) instanceof MaterializeColumnValue) {
                                columnType = ((MaterializeColumnValue) subAggr.getArgs().get(0)).getColumn().getType();
                            }
                        }
                    }
                }
            } else if (column instanceof MaterializeColumnValue) {
                columnType = ((MaterializeColumnValue) column).getColumn().getType();
            }

            if (columnType == null) {
                columnType = MaterializeDataType.TEXT; // fallback
            }
            idxTypeMap.put(i, columnType);
        }

        return idxTypeMap;
    }

    private boolean compareResult(Map<String, List<MaterializeConstant>> r1, Map<String, List<MaterializeConstant>> r2) {
        if (r1.size() != r2.size()) {
            return false;
        }
        for (Map.Entry<String, List<MaterializeConstant>> entry : r1.entrySet()) {
            String currentKey = entry.getKey();
            if (!r2.containsKey(currentKey)) {
                return false;
            }
            List<MaterializeConstant> v1 = entry.getValue();
            List<MaterializeConstant> v2 = r2.get(currentKey);
            if (v1.size() != v2.size()) {
                return false;
            }
            List<String> v1Value = new ArrayList<>(v1.stream().map(c -> c.toStringForComparison()).collect(Collectors.toList()));
            List<String> v2Value = new ArrayList<>(v2.stream().map(c -> c.toStringForComparison()).collect(Collectors.toList()));
            Collections.sort(v1Value);
            Collections.sort(v2Value);
            if (!v1Value.equals(v2Value)) {
                state.getState().getLocalState().log(v1Value.toString() + "\n" + v2Value.toString() + "\n");
                return false;
            }
        }
        return true;
    }

    private MaterializeSelect genSimpleSelect() {
        MaterializeTables tables = s.getRandomTableNonEmptyTables();

        tablesFromOuterContext = tables.getTables();
        List<MaterializeExpression> tableL = tables.getTables().stream().map(t -> new MaterializeFromTable(t, false))
                .collect(Collectors.toList());
        MaterializeExpressionGenerator exprGen = new MaterializeExpressionGenerator(state).setColumns(tables.getColumns());
        this.foldedExpr = exprGen.generateExpression(MaterializeDataType.BOOLEAN);

        MaterializeSelect select = new MaterializeSelect();

        if (Randomly.getBoolean()) {
            List<MaterializeJoin> joins = getJoins(tableL, state);
            if (joins.size() > 0) {
                select.setJoinClauses(joins);
                this.joinsInExpr = joins;
            }
        }

        select.setFromList(tableL);

        List<MaterializeExpression> fetchColumns = new ArrayList<>();
        int columnIdx = 0;
        for (MaterializeColumn c : tables.getColumns()) {
            MaterializeColumnValue cRef = MaterializeColumnValue.create(c, null);
            MaterializeAlias cAlias = new MaterializeAlias(cRef, "c" + String.valueOf(columnIdx));
            fetchColumns.add(cAlias);
            columnIdx++;
        }

        // add the expression as last fetch column
        MaterializeAlias eAlias = new MaterializeAlias(this.foldedExpr, "c" + String.valueOf(columnIdx));
        fetchColumns.add(eAlias);

        select.setFetchColumns(fetchColumns);

        Map<String, List<MaterializeConstant>> queryRes = null;
        try {
            queryRes = getQueryResult(MaterializeVisitor.asString(select), state);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            } else {
                throw new AssertionError(e.getMessage());
            }
        }
        // just ignore the empty result
        if (queryRes.get("c0").size() == 0) {
            throw new IgnoreMeException();
        }

        // save the result first
        selectResult.clear();
        selectResult.putAll(queryRes);

        // get the constant corresponding to each row from results
        List<MaterializeConstant> summary = queryRes.remove("c" + String.valueOf(columnIdx));

        Map<Integer, MaterializeDataType> columnType = getColumnTypeFromSelect(select);

        LinkedHashMap<MaterializeColumnValue, List<MaterializeConstant>> dbstate = new LinkedHashMap<>();
        // do not put the last fetch column to values
        // skip FLOAT/REAL/DECIMAL columns to avoid unreliable float equality in CASE WHEN
        for (int i = 0; i < fetchColumns.size() - 1; ++i) {
            MaterializeAlias cAlias = (MaterializeAlias) fetchColumns.get(i);
            MaterializeColumnValue cRef = (MaterializeColumnValue) cAlias.getExpression();
            MaterializeDataType colType = cRef.getColumn().getType();
            if (colType == MaterializeDataType.FLOAT || colType == MaterializeDataType.REAL || colType == MaterializeDataType.DECIMAL) {
                continue;
            }
            String columnName = "c" + String.valueOf(i);
            dbstate.put(cRef, queryRes.get(columnName));
        }
        if (dbstate.isEmpty()) {
            throw new IgnoreMeException();
        }

        foldedExpressionReturnType = columnType.get(fetchColumns.size() - 1);

        this.constantResOfFoldedExpr = new MaterializeResultMap(dbstate, summary, foldedExpressionReturnType);

        return select;
    }

    private MaterializeSelect genSelectWithCorrelatedSubquery() {
        MaterializeTables outerQueryRandomTables = s.getRandomTableNonEmptyTables();
        MaterializeTables innerQueryRandomTables = s.getRandomTableNonEmptyTables();

        List<MaterializeExpression> innerQueryFromTables = new ArrayList<>();
        for (MaterializeTable t : innerQueryRandomTables.getTables()) {
            if (!outerQueryRandomTables.isContained(t)) {
                innerQueryFromTables.add(new MaterializeFromTable(t, false));
            }
        }
        for (MaterializeTable t : outerQueryRandomTables.getTables()) {
            if (innerQueryRandomTables.isContained(t)) {
                innerQueryRandomTables.removeTable(t);

                List<MaterializeColumn> newColumns = new ArrayList<>();
                for (MaterializeColumn c : t.getColumns()) {
                    MaterializeColumn newColumn = new MaterializeColumn(c.getName(), c.getType());
                    newColumns.add(newColumn);
                }
                MaterializeTable newTable = new MaterializeTable(t.getName() + "a", newColumns, Collections.emptyList(),
                        MaterializeTable.TableType.STANDARD, Collections.emptyList(), false, true);
                for (MaterializeColumn c : newColumns) {
                    c.setTable(newTable);
                }
                innerQueryRandomTables.addTable(newTable);

                MaterializeAlias alias = new MaterializeAlias(new MaterializeFromTable(t, false), newTable.getName());
                innerQueryFromTables.add(alias);
            }
        }

        List<MaterializeColumn> innerQueryColumns = new ArrayList<>();
        innerQueryColumns.addAll(innerQueryRandomTables.getColumns());
        innerQueryColumns.addAll(outerQueryRandomTables.getColumns());

        gen = new MaterializeExpressionGenerator(state).setColumns(innerQueryColumns);

        MaterializeSelect innerQuery = new MaterializeSelect();
        innerQuery.setFromList(innerQueryFromTables);

        MaterializeExpression innerQueryWhereCondition = gen.generateExpression(MaterializeDataType.BOOLEAN);
        innerQuery.setWhereClause(innerQueryWhereCondition);

        // use aggregate function in fetch column
        MaterializeColumnValue innerQueryAggr = MaterializeColumnValue.create(Randomly.fromList(innerQueryRandomTables.getColumns()), null);
        List<MaterializeAggregateFunction> windowFunctionList = new ArrayList<>(MaterializeAggregateFunction.getAggregates(innerQueryAggr.getColumn().getType()));
        // Exclude precision-problematic aggregates
        MaterializeDataType innerColType = innerQueryAggr.getColumn().getType();
        if (innerColType == MaterializeDataType.FLOAT || innerColType == MaterializeDataType.REAL || innerColType == MaterializeDataType.DECIMAL) {
            windowFunctionList.remove(MaterializeAggregateFunction.AVG);
            windowFunctionList.remove(MaterializeAggregateFunction.SUM);
        }
        if (innerColType == MaterializeDataType.TEXT) {
            windowFunctionList.remove(MaterializeAggregateFunction.MAX);
            windowFunctionList.remove(MaterializeAggregateFunction.MIN);
        }
        if (windowFunctionList.isEmpty()) {
            windowFunctionList.add(MaterializeAggregateFunction.COUNT);
        }
        MaterializeAggregateFunction windowFunction = Randomly.fromList(windowFunctionList);
        MaterializeExpression innerQueryAggrName = new MaterializeAggregate(Arrays.asList(innerQueryAggr), windowFunction);
        innerQuery.setFetchColumns(Arrays.asList(innerQueryAggrName));

        this.foldedExpr = innerQuery;

        // outer query
        MaterializeSelect outerQuery = new MaterializeSelect();
        List<MaterializeExpression> outerQueryFromTableRefs = outerQueryRandomTables.getTables().stream().map(t -> new MaterializeFromTable(t, false)).collect(Collectors.toList());
        outerQuery.setFromList(outerQueryFromTableRefs);
        tablesFromOuterContext = outerQueryRandomTables.getTables();

        List<MaterializeExpression> fetchColumns = new ArrayList<>();
        int columnIdx = 0;
        for (MaterializeColumn c : outerQueryRandomTables.getColumns()) {
            MaterializeColumnValue cRef = MaterializeColumnValue.create(c, null);
            MaterializeAlias cAlias = new MaterializeAlias(cRef, "c" + String.valueOf(columnIdx));
            fetchColumns.add(cAlias);
            columnIdx++;
        }

        // add the expression as last fetch column
        MaterializeAlias subqueryAlias = new MaterializeAlias(innerQuery, "c" + String.valueOf(columnIdx));
        fetchColumns.add(subqueryAlias);

        outerQuery.setFetchColumns(fetchColumns);

        originalQueryString = MaterializeVisitor.asString(outerQuery);

        Map<String, List<MaterializeConstant>> queryRes = null;
        try {
            queryRes = getQueryResult(originalQueryString, state);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            } else {
                throw new AssertionError(e.getMessage());
            }
        }
        // just ignore the empty result
        if (queryRes.get("c0").size() == 0) {
            throw new IgnoreMeException();
        }

        // save the result first
        selectResult.clear();
        selectResult.putAll(queryRes);

        // get the constant corresponding to each row from results
        List<MaterializeConstant> summary = queryRes.remove("c" + String.valueOf(columnIdx));

        Map<Integer, MaterializeDataType> columnType = getColumnTypeFromSelect(outerQuery);

        LinkedHashMap<MaterializeColumnValue, List<MaterializeConstant>> dbstate = new LinkedHashMap<>();
        // do not put the last fetch column to values
        // skip FLOAT/REAL/DECIMAL columns to avoid unreliable float equality in CASE WHEN
        for (int i = 0; i < fetchColumns.size() - 1; ++i) {
            MaterializeAlias cAlias = (MaterializeAlias) fetchColumns.get(i);
            MaterializeColumnValue cRef = (MaterializeColumnValue) cAlias.getExpression();
            MaterializeDataType colType = cRef.getColumn().getType();
            if (colType == MaterializeDataType.FLOAT || colType == MaterializeDataType.REAL || colType == MaterializeDataType.DECIMAL) {
                continue;
            }
            String columnName = "c" + String.valueOf(i);
            dbstate.put(cRef, queryRes.get(columnName));
        }
        if (dbstate.isEmpty()) {
            throw new IgnoreMeException();
        }

        foldedExpressionReturnType = columnType.get(fetchColumns.size() - 1);

        this.constantResOfFoldedExpr = new MaterializeResultMap(dbstate, summary, foldedExpressionReturnType);

        return outerQuery;
    }

    private static String materializeDataTypeToSqlString(MaterializeDataType type) {
        switch (type) {
        case BOOLEAN:
            return "BOOLEAN";
        case INT:
            return "INT";
        case TEXT:
            return "TEXT";
        case DECIMAL:
            return "DECIMAL";
        case FLOAT:
            return "REAL";
        case REAL:
            return "FLOAT";
        case BIT:
            return "INT";
        default:
            throw new AssertionError(type);
        }
    }

    @Override
    public String getLastQueryString() {
        return originalQueryString;
    }

    @Override
    public Reproducer<MaterializeGlobalState> getLastReproducer() {
        return reproducer;
    }
}
