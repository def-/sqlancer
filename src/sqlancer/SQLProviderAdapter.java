package sqlancer;

import java.util.List;

import sqlancer.common.log.LoggableFactory;
import sqlancer.common.log.SQLLoggableFactory;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTable;

public abstract class SQLProviderAdapter<G extends SQLGlobalState<O, ? extends AbstractSchema<G, ?>>, O extends DBMSSpecificOptions<? extends OracleFactory<G>>>
        extends ProviderAdapter<G, O, SQLConnection> {
    protected SQLProviderAdapter(Class<G> globalClass, Class<O> optionClass) {
        super(globalClass, optionClass);
    }

    @Override
    public LoggableFactory getLoggableFactory() {
        return new SQLLoggableFactory();
    }

    @Override
    protected void checkViewsAreValid(G globalState) {
        List<? extends AbstractTable<?, ?, ?>> views = globalState.getSchema().getViews();
        boolean droppedAny = false;
        for (AbstractTable<?, ?, ?> view : views) {
            SQLQueryAdapter q = new SQLQueryAdapter("SELECT 1 FROM " + view.getName() + " LIMIT 1");
            try {
                if (!q.execute(globalState)) {
                    dropView(globalState, view.getName());
                    droppedAny = true;
                }
            } catch (Throwable t) {
                dropView(globalState, view.getName());
                droppedAny = true;
            }
        }
        if (droppedAny) {
            try {
                globalState.updateSchema();
            } catch (Exception e) {
                throw new IgnoreMeException();
            }
        }
    }

    private void dropView(G globalState, String viewName) {
        try {
            globalState.executeStatement(new SQLQueryAdapter("DROP VIEW " + viewName, true));
        } catch (Throwable t2) {
            // Ignore errors — the view may already be gone or the schema update
            // inside executeStatement may have failed. The caller will force a
            // schema refresh after all drops.
        }
    }
}
