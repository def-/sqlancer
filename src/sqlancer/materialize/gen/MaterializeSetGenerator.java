package sqlancer.materialize.gen;

import java.util.ArrayList;
import java.util.List;

public class MaterializeSetGenerator {

    private static final String[] OPTIMIZER_FLAGS = { "enable_eager_delta_joins", "enable_reduce_mfp_fusion",
            "enable_variadic_left_join_lowering", "enable_cast_elimination", "enable_new_outer_join_lowering",
            "enable_cardinality_estimates", "enable_join_prioritize_arranged",
            "enable_projection_pushdown_after_relation_cse", "enable_eq_classes_withholding_errors",
            "enable_consolidate_after_union_negate", "enable_letrec_fixpoint_analysis",
            "enable_less_reduce_in_eqprop", "enable_dequadratic_eqprop_map" };

    private static final String PERSIST_FAST_PATH_LIMIT = "persist_fast_path_limit";
    private static final int[] PERSIST_FAST_PATH_LIMIT_VALUES = { 0, 1, 25, 1000 };

    private MaterializeSetGenerator() {
    }

    public static List<String> getAllOptimizerStatements() {
        List<String> result = new ArrayList<>();
        for (String flag : OPTIMIZER_FLAGS) {
            result.add("ALTER SYSTEM SET " + flag + " = true");
            result.add("ALTER SYSTEM SET " + flag + " = false");
        }
        for (int value : PERSIST_FAST_PATH_LIMIT_VALUES) {
            result.add("ALTER SYSTEM SET " + PERSIST_FAST_PATH_LIMIT + " = " + value);
        }
        return result;
    }
}
