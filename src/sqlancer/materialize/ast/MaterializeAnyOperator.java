package sqlancer.materialize.ast;

import sqlancer.materialize.ast.MaterializeBinaryComparisonOperation.MaterializeBinaryComparisonOperator;

public class MaterializeAnyOperator implements MaterializeExpression {
    private final MaterializeExpression leftExpr;
    private final MaterializeExpression rightExpr;
    private final MaterializeBinaryComparisonOperator op;

    public MaterializeAnyOperator(MaterializeExpression leftExpr, MaterializeExpression rightExpr, MaterializeBinaryComparisonOperator op) {
        this.leftExpr = leftExpr;
        this.rightExpr = rightExpr;
        this.op = op;
    }

    public MaterializeExpression getLeftExpr() {
        return leftExpr;
    }

    public MaterializeExpression getRightExpr() {
        return rightExpr;
    }

    public String getOperator() {
        return op.getTextRepresentation();
    }
}
