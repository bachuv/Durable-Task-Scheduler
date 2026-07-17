// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package io.durabletask.samples;

import com.microsoft.durabletask.DurableTaskClient;
import com.microsoft.durabletask.DurableTaskGrpcClientBuilder;
import com.microsoft.durabletask.DurableTaskGrpcWorker;
import com.microsoft.durabletask.DurableTaskGrpcWorkerBuilder;
import com.microsoft.durabletask.OrchestrationMetadata;
import com.microsoft.durabletask.OrchestrationRuntimeStatus;
import com.microsoft.durabletask.TaskActivity;
import com.microsoft.durabletask.TaskActivityFactory;
import com.microsoft.durabletask.TaskOrchestration;
import com.microsoft.durabletask.TaskOrchestrationFactory;
import com.microsoft.durabletask.azuremanaged.DurableTaskSchedulerClientExtensions;
import com.microsoft.durabletask.azuremanaged.DurableTaskSchedulerWorkerExtensions;
import com.microsoft.durabletask.exporthistory.ExportDestination;
import com.microsoft.durabletask.exporthistory.ExportFormat;
import com.microsoft.durabletask.exporthistory.ExportFormatKind;
import com.microsoft.durabletask.exporthistory.ExportHistoryClient;
import com.microsoft.durabletask.exporthistory.ExportHistoryClientExtensions;
import com.microsoft.durabletask.exporthistory.ExportHistoryJobClient;
import com.microsoft.durabletask.exporthistory.ExportHistoryStorageOptions;
import com.microsoft.durabletask.exporthistory.ExportHistoryWorkerExtensions;
import com.microsoft.durabletask.exporthistory.ExportJobCreationOptions;
import com.microsoft.durabletask.exporthistory.ExportJobDescription;
import com.microsoft.durabletask.exporthistory.ExportJobQuery;
import com.microsoft.durabletask.exporthistory.ExportJobQueryResult;
import com.microsoft.durabletask.exporthistory.ExportJobStatus;
import com.microsoft.durabletask.exporthistory.ExportMode;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Demonstrates the complete history-export job lifecycle with Durable Task Scheduler.
 */
final class HistoryExportSample {

    private static final String ORCHESTRATION_NAME = "HistoryExportSquare";
    private static final String ACTIVITY_NAME = "SquareNumber";
    private static final int INSTANCE_COUNT = 5;
    private static final Duration INSTANCE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private HistoryExportSample() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            runSample();
        } catch (Exception exception) {
            exception.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static void runSample() throws Exception {
        String schedulerConnectionString = envOrDefault(
                "DURABLE_TASK_CONNECTION_STRING",
                "Endpoint=http://localhost:8080;TaskHub=default;Authentication=None");
        String storageConnectionString = envOrDefault(
                "EXPORT_HISTORY_STORAGE_CONNECTION_STRING",
                "UseDevelopmentStorage=true");
        String containerName = envOrDefault("EXPORT_HISTORY_CONTAINER_NAME", "history-export-sample");
        String defaultPrefix = envOrDefault("EXPORT_HISTORY_PREFIX", "java-sample/");

        ExportHistoryStorageOptions storageOptions = new ExportHistoryStorageOptions()
                .setConnectionString(storageConnectionString)
                .setContainerName(containerName)
                .setPrefix(defaultPrefix);

        DurableTaskGrpcClientBuilder clientBuilder = new DurableTaskGrpcClientBuilder();
        DurableTaskSchedulerClientExtensions.useDurableTaskScheduler(clientBuilder, schedulerConnectionString);
        DurableTaskClient client = clientBuilder.build();

        DurableTaskGrpcWorkerBuilder workerBuilder = new DurableTaskGrpcWorkerBuilder();
        DurableTaskSchedulerWorkerExtensions.useDurableTaskScheduler(workerBuilder, schedulerConnectionString);
        registerSampleWorkload(workerBuilder);
        ExportHistoryWorkerExtensions.useExportHistory(workerBuilder, storageOptions, client);

        try (DurableTaskClient durableTaskClient = client;
             DurableTaskGrpcWorker worker = workerBuilder.build()) {
            worker.start();
            Thread.sleep(2000);

            ExportHistoryClient exportClient =
                    ExportHistoryClientExtensions.useExportHistory(durableTaskClient, storageOptions);
            String runId = UUID.randomUUID().toString().substring(0, 8);
            String jobIdPrefix = "java-history-" + runId + "-";
            Instant sampleStartedAt = Instant.now();

            section("1. Seed terminal orchestrations");
            Instant completedTimeFrom = Instant.now().minusSeconds(1);
            seedCompletedOrchestrations(durableTaskClient, INSTANCE_COUNT);
            Instant completedTimeTo = Instant.now();

                section("2. Run a filtered batch export using the default destination and JSONL + gzip");
            ExportHistoryJobClient jsonlJob = exportClient.createJob(
                    new ExportJobCreationOptions(jobIdPrefix + "batch-jsonl")
                            .setMode(ExportMode.BATCH)
                            .setCompletedTimeFrom(completedTimeFrom)
                            .setCompletedTimeTo(completedTimeTo)
                            .setRuntimeStatus(Collections.singletonList(OrchestrationRuntimeStatus.COMPLETED))
                        .setMaxInstancesPerBatch(2));
            ExportJobDescription jsonlResult = waitForTerminalStatus(jsonlJob);
            printJob(jsonlResult);
            requireCompleted(jsonlResult);

            section("3. Retrieve the batch job by ID");
            printJob(exportClient.getJob(jsonlJob.getJobId()));

            section("4. Run the same window using uncompressed JSON");
            ExportHistoryJobClient jsonJob = exportClient.createJob(
                    new ExportJobCreationOptions(jobIdPrefix + "batch-json")
                            .setMode(ExportMode.BATCH)
                            .setCompletedTimeFrom(completedTimeFrom)
                            .setCompletedTimeTo(completedTimeTo)
                            .setFormat(new ExportFormat(
                                    ExportFormatKind.JSON,
                                    ExportFormat.DEFAULT_SCHEMA_VERSION))
                            .setDestination(destination(containerName, "java-sample/batch-json/")));
            ExportJobDescription jsonResult = waitForTerminalStatus(jsonJob);
            printJob(jsonResult);
            requireCompleted(jsonResult);

            section("5. List jobs, then filter and page the results");
            ExportJobQueryResult allJobs = exportClient.listJobs(null);
            System.out.println("All export jobs in first page: " + allJobs.getJobs().size());

            ExportJobQuery completedJobsQuery = new ExportJobQuery()
                    .setStatus(ExportJobStatus.COMPLETED)
                    .setJobIdPrefix(jobIdPrefix)
                    .setCreatedFrom(sampleStartedAt.minusSeconds(1))
                    .setCreatedTo(Instant.now().plusSeconds(1))
                    .setPageSize(1);
            printAllPages(exportClient, completedJobsQuery);

            section("6. Start a continuous export, observe progress, then delete it");
            Instant continuousFrom = Instant.now().minusSeconds(1);
            ExportHistoryJobClient continuousJob = exportClient.createJob(
                    new ExportJobCreationOptions(jobIdPrefix + "continuous")
                            .setMode(ExportMode.CONTINUOUS)
                            .setCompletedTimeFrom(continuousFrom)
                            .setDestination(destination(containerName, "java-sample/continuous/")));
            try {
                seedCompletedOrchestrations(durableTaskClient, 1);
                ExportJobDescription continuousProgress = waitForFirstExport(continuousJob);
                printJob(continuousProgress);
                if (continuousProgress.getStatus() == ExportJobStatus.FAILED) {
                    throw new IllegalStateException(
                            "Continuous export job failed: " + continuousProgress.getLastError());
                }
            } finally {
                continuousJob.delete();
                System.out.println("Deleted continuous job: " + continuousJob.getJobId());
            }

            section("Done");
            System.out.println("Exported history is in container '" + containerName + "'.");
            System.out.println("Default prefix: " + defaultPrefix);
        }
    }

    private static void registerSampleWorkload(DurableTaskGrpcWorkerBuilder workerBuilder) {
        workerBuilder.addActivity(new TaskActivityFactory() {
            @Override
            public String getName() {
                return ACTIVITY_NAME;
            }

            @Override
            public TaskActivity create() {
                return context -> {
                    int input = context.getInput(Integer.class);
                    return input * input;
                };
            }
        });

        workerBuilder.addOrchestration(new TaskOrchestrationFactory() {
            @Override
            public String getName() {
                return ORCHESTRATION_NAME;
            }

            @Override
            public TaskOrchestration create() {
                return context -> {
                    int input = context.getInput(Integer.class);
                    int result = context.callActivity(ACTIVITY_NAME, input, Integer.class).await();
                    context.complete(result);
                };
            }
        });
    }

    private static void seedCompletedOrchestrations(DurableTaskClient client, int count)
            throws TimeoutException {
        for (int value = 1; value <= count; value++) {
            String instanceId = client.scheduleNewOrchestrationInstance(ORCHESTRATION_NAME, value);
            OrchestrationMetadata completed =
                    client.waitForInstanceCompletion(instanceId, INSTANCE_TIMEOUT, true);
            System.out.printf(
                    "Completed %s: %d -> %d%n",
                    instanceId,
                    value,
                    completed.readOutputAs(Integer.class));
        }
    }

    private static ExportJobDescription waitForTerminalStatus(ExportHistoryJobClient jobClient)
            throws InterruptedException, TimeoutException {
        Instant deadline = Instant.now().plus(EXPORT_TIMEOUT);
        ExportJobDescription description = jobClient.describe();
        while (description.getStatus() != ExportJobStatus.COMPLETED
                && description.getStatus() != ExportJobStatus.FAILED) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Timed out waiting for export job " + jobClient.getJobId());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
            description = jobClient.describe();
        }
        return description;
    }

    private static ExportJobDescription waitForFirstExport(ExportHistoryJobClient jobClient)
            throws InterruptedException, TimeoutException {
        Instant deadline = Instant.now().plus(EXPORT_TIMEOUT);
        ExportJobDescription description = jobClient.describe();
        while (description.getExportedInstances() == 0 && description.getStatus() != ExportJobStatus.FAILED) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Timed out waiting for continuous export job " + jobClient.getJobId());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
            description = jobClient.describe();
        }
        return description;
    }

    private static ExportDestination destination(String containerName, String prefix) {
        ExportDestination destination = new ExportDestination(containerName);
        destination.setPrefix(prefix);
        return destination;
    }

    private static void printAllPages(ExportHistoryClient exportClient, ExportJobQuery query) {
        int pageNumber = 1;
        String continuationToken = null;
        do {
            query.setContinuationToken(continuationToken);
            ExportJobQueryResult page = exportClient.listJobs(query);
            if (!page.getJobs().isEmpty()) {
                System.out.println("Filtered page " + pageNumber + ":");
                for (ExportJobDescription job : page.getJobs()) {
                    System.out.println("  " + job.getJobId() + " (" + job.getStatus() + ")");
                }
                pageNumber++;
            }
            continuationToken = page.getContinuationToken();
        } while (continuationToken != null);
        System.out.println("No more filtered pages.");
    }

    private static void printJob(ExportJobDescription job) {
        System.out.println("Job ID:             " + job.getJobId());
        System.out.println("Status:             " + job.getStatus());
        System.out.println("Orchestrator ID:    " + job.getOrchestratorInstanceId());
        System.out.println("Scanned instances:  " + job.getScannedInstances());
        System.out.println("Exported instances: " + job.getExportedInstances());
        if (job.getLastError() != null) {
            System.out.println("Last error:         " + job.getLastError());
        }
    }

    private static void requireCompleted(ExportJobDescription job) {
        if (job.getStatus() != ExportJobStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Export job " + job.getJobId() + " ended with status " + job.getStatus());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}