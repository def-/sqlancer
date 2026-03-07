package sqlancer.materialize.ast;

public class MaterializeExists implements MaterializeExpression {

    private final MaterializeExpression select;
    private boolean negated = false;

    public MaterializeExists(MaterializeExpression select, boolean negated) {
        this.select = select;
        this.negated = negated;
    }

    public void setNegated(boolean negated) {
        this.negated = negated;
    }

    public boolean getNegated() {
        return this.negated;
    }

    public MaterializeExpression getExpression() {
        return select;
    }
}
