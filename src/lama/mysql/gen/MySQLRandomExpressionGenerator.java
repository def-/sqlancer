package lama.mysql.gen;

import java.util.List;

import lama.Randomly;
import lama.mysql.MySQLSchema.MySQLColumn;
import lama.mysql.MySQLSchema.MySQLRowValue;
import lama.mysql.ast.MySQLBinaryLogicalOperation;
import lama.mysql.ast.MySQLBinaryLogicalOperation.MySQLBinaryLogicalOperator;
import lama.mysql.ast.MySQLColumnValue;
import lama.mysql.ast.MySQLComputableFunction;
import lama.mysql.ast.MySQLComputableFunction.MySQLFunction;
import lama.mysql.ast.MySQLConstant;
import lama.mysql.ast.MySQLExpression;
import lama.mysql.ast.MySQLUnaryNotOperator;
import lama.mysql.ast.MySQLUnaryPostfixOperator;

public class MySQLRandomExpressionGenerator {

	private final static int MAX_DEPTH = 10;

	public static MySQLExpression generateRandomExpression(List<MySQLColumn> columns, MySQLRowValue rowVal,
			Randomly r) {
		return gen(columns, rowVal, 0, r);
	}

	private enum Actions {
		COLUMN, LITERAL, NOT, UNARY_POSTFIX, FUNCTION, BINARY_LOGICAL_OPERATOR;
	}

	public static MySQLExpression gen(List<MySQLColumn> columns, MySQLRowValue rowVal, int depth, Randomly r) {
		if (depth > MAX_DEPTH) {
			if (Randomly.getBoolean()) {
				return generateColumn(columns, rowVal);
			} else {
				return generateLiteral(r);
			}
		}
		switch (Randomly.fromOptions(Actions.values())) {
		case COLUMN:
			return generateColumn(columns, rowVal);
		case LITERAL:
			return generateLiteral(r);
		case NOT:
			return new MySQLUnaryNotOperator(gen(columns, rowVal, depth + 1, r));
		case UNARY_POSTFIX:
			return new MySQLUnaryPostfixOperator(gen(columns, rowVal, depth + 1, r),
					Randomly.fromOptions(MySQLUnaryPostfixOperator.UnaryPostfixOperator.values()),
					Randomly.getBoolean());
		case FUNCTION:
			return getFunction(columns, rowVal, depth + 1, r);
		case BINARY_LOGICAL_OPERATOR:
			return new MySQLBinaryLogicalOperation(gen(columns, rowVal, depth + 1, r), gen(columns, rowVal, depth + 1, r), MySQLBinaryLogicalOperator.getRandom());
		default:
			throw new AssertionError();
		}
	}

	private static MySQLExpression getFunction(List<MySQLColumn> columns, MySQLRowValue rowVal, int depth, Randomly r) {
		MySQLFunction func = MySQLFunction.getRandomFunction();
		int nrArgs = func.getNrArgs();
		if (func.isVariadic()) {
			nrArgs += Randomly.smallNumber();
		}
		MySQLExpression[] args = new MySQLExpression[nrArgs];
		for (int i = 0; i < args.length; i++) {
			args[i] = gen(columns, rowVal, depth + 1, r);
		}
		return new MySQLComputableFunction(func, args);
	}

	private static MySQLExpression generateLiteral(Randomly r) {
		return MySQLConstant.createIntConstant((int) r.getInteger());
	}

	private static MySQLExpression generateColumn(List<MySQLColumn> columns, MySQLRowValue rowVal) {
		MySQLColumn c = Randomly.fromList(columns);
		MySQLConstant val;
		if (rowVal == null) {
			val = null;
		} else {
			val = rowVal.getValues().get(c);
		}
		return MySQLColumnValue.create(c, val);
	}

}