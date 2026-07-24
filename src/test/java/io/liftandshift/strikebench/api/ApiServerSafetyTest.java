package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiServerSafetyTest {
    @Test void analysisDatasetResolutionNeverFallsThroughToObservedOnFailure() {
        assertThat(ApiServer.resolveAnalysisContext(null, "local").datasetId())
                .isEqualTo(DatasetService.OBSERVED);

        Db db = TestDb.fresh();
        DatasetService datasets = new DatasetService(db, Clock.systemUTC());
        db.close();

        assertThatThrownBy(() -> ApiServer.resolveAnalysisContext(datasets, "local"))
                .isInstanceOf(RuntimeException.class);
    }
}
