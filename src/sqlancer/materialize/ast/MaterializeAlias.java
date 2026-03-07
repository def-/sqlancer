package sqlancer.materialize.ast;

public class MaterializeAlias implements MaterializeExpression {

    private final MaterializeExpression expr;
    private final String alias;

    public MaterializeAlias(MaterializeExpression expr, String alias) {
        this.expr = expr;
        this.alias = alias;
    }

    public MaterializeExpression getExpression() {
        return expr;
    }

    public String getAlias() {
        return alias;
    }

}
