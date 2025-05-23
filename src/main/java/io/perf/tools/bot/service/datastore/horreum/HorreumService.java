package io.perf.tools.bot.service.datastore.horreum;

import io.hyperfoil.tools.HorreumClient;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.api.data.ExportedLabelValues;
import io.hyperfoil.tools.horreum.api.data.LabelValueMap;
import io.hyperfoil.tools.horreum.api.services.ExperimentService;
import io.hyperfoil.tools.horreum.api.services.RunService;
import io.perf.tools.bot.model.ProjectConfig;
import io.perf.tools.bot.service.ConfigService;
import io.perf.tools.bot.service.datastore.Datastore;
import io.perf.tools.bot.service.datastore.horreum.util.ExperimentResultConverter;
import io.perf.tools.bot.service.datastore.horreum.util.LabelValueMapConverter;
import io.quarkus.arc.impl.Identified;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.net.ssl.SSLContext;
import java.util.List;

@ApplicationScoped
public class HorreumService implements Datastore {
    public static final String LATEST_RUN = "latest";

    @ConfigProperty(name = "proxy.datastore.horreum.url")
    String horreumUrl;

    @Inject
    ConfigService configService;

    @Inject
    LabelValueMapConverter labelValueMapConverter;

    @Inject
    ExperimentResultConverter experimentResultConverter;

    @Inject
    @Identifier("horreumSslContext")
    SSLContext horreumSslContext;

    private HorreumClient createHorreumClient(ProjectConfig config) {
        HorreumClient.Builder builder = new HorreumClient.Builder().horreumUrl(horreumUrl).horreumApiKey(config.horreumKey)
                .sslContext(horreumSslContext);
        return builder.build();
    }

    public LabelValueMap getRun(ProjectConfig config, String horreumRunId) {

        try (HorreumClient client = createHorreumClient(config)) {
            List<ExportedLabelValues> labelValues = client.runService.getRunLabelValues(Integer.parseInt(horreumRunId), null,
                    null, null, 1000, 0,
                    null, null, false);
            // assuming we have one single dataset
            // TODO: implement validation on the result to return meaningful errors in case something is not expected
            return labelValues.getFirst().values;
        }
    }

    @Override
    public String getRun(String repo, String horreumRunId) {

        ProjectConfig config = configService.getConfig(repo);
        try (HorreumClient client = createHorreumClient(config)) {
            List<ExportedLabelValues> labelValues;
            if (LATEST_RUN.equals(horreumRunId)) {
                // we need only one run, the latest one
                // TODO: I should filter runs by pull request id using the filter param
                labelValues = client.testService.getTestLabelValues(Integer.parseInt(config.horreumTestId), null, null, null,
                        false, true, "id", "descending", 1, 0, null, null, false);
            } else {
                labelValues = client.runService.getRunLabelValues(Integer.parseInt(horreumRunId), null, null, null, 1000, 0,
                        null, null, false);
            }
            // assuming we have one single dataset
            // TODO: implement validation on the result to return meaningful errors in case something is not expected
            LabelValueMap labelValueMap = labelValues.getFirst().values;
            return labelValueMapConverter.serialize(labelValueMap);
        }
    }

    /**
     * Compare the provided run against the baseline configured in Horreum
     * @param horreumRunId id of the run in Horreum
     */
    @Override
    public String compare(String repo, String horreumRunId) {
        ProjectConfig config = configService.getConfig(repo);
        try (HorreumClient client = createHorreumClient(config)) {
            RunService.RunSummary run;
            if (LATEST_RUN.equals(horreumRunId)) {
                // we need only one run, the latest one
                // TODO: I should filter runs by pull request id
                RunService.RunsSummary summary = client.runService.listTestRuns(Integer.parseInt(config.horreumTestId), false,
                        1, 1, "id",
                        SortDirection.Descending);
                run = summary.runs.getFirst();
            } else {
                run = client.runService.getRunSummary(Integer.parseInt(horreumRunId));
            }
            Log.info("Comparing run " + run.id);
            // assumption that there is only one dataset
            List<ExperimentService.ExperimentResult> comparisonResults = client.experimentService.runExperiments(
                    run.datasets[0]);
            return String.join("\n", comparisonResults.stream()
                    .map(experimentResult -> experimentResultConverter.serialize(experimentResult)).toList());
        }
    }
}
