package sqlancer.materialize.ast;

// The ExpressionBag is not a built-in SQL feature,
// but rather a utility class used in CODDTest's oracle construction
// to substitute expressions with their corresponding constant values.
public class MaterializeExpressionBag implements MaterializeExpression {
    private MaterializeExpression innerExpr;

    public MaterializeExpressionBag(MaterializeExpression innerExpr) {
        this.innerExpr = innerExpr;
    }

    public void updateInnerExpr(MaterializeExpression innerExpr) {
        this.innerExpr = innerExpr;
    }

    public MaterializeExpression getInnerExpr() {
        return innerExpr;
    }

}
