package sqlancer.dqp.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sqlancer.Main;
import sqlancer.dbms.TestConfig;

public class TestMaterializeDQP {

    @Test
    public void testMaterializeDQP() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.MATERIALIZE_ENV));
        assertEquals(0,
                Main.executeMain(new String[] { "--random-seed", "0", "--timeout-seconds", TestConfig.SECONDS,
                        "--num-threads", "1", "--num-queries", TestConfig.NUM_QUERIES, "--username", "materialize",
                        "materialize", "--oracle", "DQP" }));
    }

}
