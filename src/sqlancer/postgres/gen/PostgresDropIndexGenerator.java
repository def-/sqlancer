package sqlancer.postgres.gen;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresSchema.PostgresIndex;
import sqlancer.postgres.PostgresSchema.PostgresTable;

public final class PostgresDropIndexGenerator {

    private PostgresDropIndexGenerator() {
    }

    public static SQLQueryAdapter create(PostgresGlobalState globalState) {
        PostgresTable randomTable = globalState.getSchema().getRandomTable();
        List<PostgresIndex> indexes = randomTable.getIndexes();
        StringBuilder sb = new StringBuilder();
        sb.append("DROP INDEX ");
        if (Randomly.getBoolean() || indexes.isEmpty()) {
            sb.append("IF EXISTS ");
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                if (indexes.isEmpty() || Randomly.getBoolean()) {
                    sb.append(DBMSCommon.createIndexNameWithTable(randomTable.getName(), Randomly.smallNumber()));
                } else {
                    sb.append(Randomly.fromList(indexes).getIndexName());
                }
            }
        } else {
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(Randomly.fromList(indexes).getIndexName());
            }
        }
        if (Randomly.getBoolean()) {
            sb.append(" ");
            sb.append(Randomly.fromOptions("CASCADE", "RESTRICT"));
        }
        return new SQLQueryAdapter(sb.toString(),
                ExpectedErrors.from("cannot drop desired object(s) because other objects depend on them",
                        "cannot drop index", "does not exist"),
                true);
    }

}
