package sqlancer.materialize.ast;

import java.util.LinkedHashMap;
import java.util.List;

import sqlancer.materialize.MaterializeSchema.MaterializeDataType;

public class MaterializeResultMap implements MaterializeExpression {
    private final LinkedHashMap<MaterializeColumnValue, List<MaterializeConstant>> DBStates;
    private final List<MaterializeConstant> results;
    MaterializeDataType resultType;

    public MaterializeResultMap(LinkedHashMap<MaterializeColumnValue, List<MaterializeConstant>> s, List<MaterializeConstant> r, MaterializeDataType rt) {
        this.DBStates = s;
        this.results = r;
        this.resultType = rt;
        if (s.get(s.keySet().iterator().next()).size() != r.size()) {
            throw new AssertionError();
        }
    }

    public LinkedHashMap<MaterializeColumnValue, List<MaterializeConstant>> getDbStates() {
        return this.DBStates;
    }

    public List<MaterializeConstant> getResult() {
        return this.results;
    }

    public MaterializeDataType getResultType() {
        return this.resultType;
    }
}
