package sqlancer.materialize.ast;

import java.util.LinkedHashMap;
import java.util.List;

import sqlancer.materialize.MaterializeSchema.MaterializeColumn;

public class MaterializeValues implements MaterializeExpression {

    private final LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> values;

    public MaterializeValues(LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> v) {
        this.values = v;
    }

    public LinkedHashMap<MaterializeColumn, List<MaterializeConstant>> getValues() {
        return this.values;
    }
}
