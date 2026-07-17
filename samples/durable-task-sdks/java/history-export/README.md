# History Export

| | |
|-|-|
| **Language** | Java 21 |
| **SDK** | Durable Task SDK |
| **Backend** | Durable Task Scheduler |

This sample exports the event history of terminal orchestrations from Durable Task Scheduler (DTS) to Azure Blob
Storage. It combines the end-to-end walkthrough from the Python sample with the broader job-management operations
shown by the .NET sample.

The program demonstrates:

1. Registering the history-export entity, orchestrators, and activities on a DTS worker
2. Running sample orchestrations to create terminal history
3. Creating and polling a batch export job
4. Filtering by orchestration runtime status and controlling batch size
5. Exporting as compressed JSONL and uncompressed JSON
6. Using both a configured default destination and a per-job destination override
7. Getting a job by ID and reading its progress
8. Listing, filtering, and paging export jobs
9. Starting a continuous export and deleting it after it exports newly completed work

The worker, client, and walkthrough run in one process so the complete lifecycle is visible from a single command.
This is a Durable Task SDK sample; it does not require Azure Functions.

## Prerequisites

- [Java 21](https://learn.microsoft.com/java/openjdk/download)
- [Docker](https://www.docker.com/products/docker-desktop)
- Version `1.10.0` of the `durabletask-client`, `durabletask-azuremanaged`, and
        `durabletask-exporthistory` packages

## Quick Run With The DTS Emulator

### 1. Start DTS and Azurite

```bash
docker compose up -d
```

This starts:

- DTS emulator gRPC endpoint at `http://localhost:8080`
- DTS dashboard at `http://localhost:8082`
- Azurite Blob service at `http://localhost:10000`

The sample automatically uses these local defaults:

```text
DURABLE_TASK_CONNECTION_STRING=Endpoint=http://localhost:8080;TaskHub=default;Authentication=None
EXPORT_HISTORY_STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true
EXPORT_HISTORY_CONTAINER_NAME=history-export-sample
EXPORT_HISTORY_PREFIX=java-sample/
```

### 2. Run the sample

Windows:

```powershell
.\gradlew.bat run
```

Linux or macOS:

```bash
./gradlew run
```

The output is divided into six stages matching the numbered walkthrough in
`HistoryExportSample.java`. A successful run ends with output similar to:

```text
=== 6. Start a continuous export, observe progress, then delete it ===
Job ID:             java-history-1234abcd-continuous
Status:             ACTIVE
Scanned instances:  <count>
Exported instances: <count>
Deleted continuous job: java-history-1234abcd-continuous

=== Done ===
Exported history is in container 'history-export-sample'.
Default prefix: java-sample/
```

Open `http://localhost:8082` and select the `default` task hub to inspect the sample and export orchestrations.

### 3. Inspect the exported history

The sample writes to the `history-export-sample` container:

| Job | Blob prefix | Format |
|-----|-------------|--------|
| First batch | `java-sample/` | `.jsonl.gz` |
| Second batch | `java-sample/batch-json/` | `.json` |
| Continuous | `java-sample/continuous/` | `.jsonl.gz` |

Each `.jsonl.gz` file contains one history event per line after decompression. Each `.json` file contains one JSON
array of history events.

### 4. Stop the local services

```bash
docker compose down -v
```

## Run With A Deployed DTS Instance

Your identity needs the **Durable Task Data Contributor** role on the task hub. Authenticate locally with Azure CLI
or another credential supported by `DefaultAzureCredential`, then set the DTS and Storage values.

PowerShell:

```powershell
$env:DURABLE_TASK_CONNECTION_STRING = "Endpoint=<scheduler-endpoint>;TaskHub=<task-hub>;Authentication=DefaultAzure"
$env:EXPORT_HISTORY_STORAGE_CONNECTION_STRING = "<storage-connection-string>"
$env:EXPORT_HISTORY_CONTAINER_NAME = "history-export-sample"
.\gradlew.bat run
```

Bash:

```bash
export DURABLE_TASK_CONNECTION_STRING="Endpoint=<scheduler-endpoint>;TaskHub=<task-hub>;Authentication=DefaultAzure"
export EXPORT_HISTORY_STORAGE_CONNECTION_STRING="<storage-connection-string>"
export EXPORT_HISTORY_CONTAINER_NAME="history-export-sample"
./gradlew run
```

The DTS connection string must include `Endpoint`, `TaskHub`, and `Authentication`. The Storage credential must be
able to create the configured container and write blobs.

## Configuration

| Environment variable | Purpose | Default |
|----------------------|---------|---------|
| `DURABLE_TASK_CONNECTION_STRING` | DTS endpoint, task hub, and authentication | Local DTS emulator |
| `EXPORT_HISTORY_STORAGE_CONNECTION_STRING` | Blob destination authentication | `UseDevelopmentStorage=true` |
| `EXPORT_HISTORY_CONTAINER_NAME` | Default destination container | `history-export-sample` |
| `EXPORT_HISTORY_PREFIX` | Default blob prefix | `java-sample/` |

## Code Walkthrough

### Register export support on the worker

The export operation is itself durable. The worker must register the export entity, orchestrators, and activities,
and the activities need a client connected to the same DTS task hub:

```java
DurableTaskSchedulerWorkerExtensions.useDurableTaskScheduler(workerBuilder, schedulerConnectionString);
registerSampleWorkload(workerBuilder);
ExportHistoryWorkerExtensions.useExportHistory(workerBuilder, storageOptions, client);
```

### Create the export client

The export client manages jobs while the worker performs the exports:

```java
ExportHistoryClient exportClient =
        ExportHistoryClientExtensions.useExportHistory(durableTaskClient, storageOptions);
```

### Create and await a batch export

The first job exports only completed instances, fetches two instances per batch, and uses the default Blob
destination and compressed JSONL format:

```java
ExportHistoryJobClient job = exportClient.createJob(
        new ExportJobCreationOptions(jobId)
                .setMode(ExportMode.BATCH)
                .setCompletedTimeFrom(windowStart)
                .setCompletedTimeTo(windowEnd)
                .setRuntimeStatus(Collections.singletonList(OrchestrationRuntimeStatus.COMPLETED))
                .setMaxInstancesPerBatch(2));

ExportJobDescription result = waitForTerminalStatus(job);
```

### Get, list, filter, and page jobs

```java
ExportJobDescription job = exportClient.getJob(jobId);

ExportJobQuery query = new ExportJobQuery()
        .setStatus(ExportJobStatus.COMPLETED)
        .setJobIdPrefix(jobIdPrefix)
        .setPageSize(1);

ExportJobQueryResult page = exportClient.listJobs(query);
query.setContinuationToken(page.getContinuationToken());
ExportJobQueryResult nextPage = exportClient.listJobs(query);
```

### Run and delete a continuous export

A continuous job has a lower completion-time bound but no upper bound. It keeps scanning newly completed instances
until it is deleted. Its count can include export-management orchestrations that also become terminal inside the
window:

```java
ExportHistoryJobClient continuousJob = exportClient.createJob(
        new ExportJobCreationOptions(jobId)
                .setMode(ExportMode.CONTINUOUS)
                .setCompletedTimeFrom(Instant.now()));

try {
    // Run work and inspect progress.
} finally {
    continuousJob.delete();
}
```

## Troubleshooting

- **`UNIMPLEMENTED` from DTS:** use the latest DTS emulator. History export requires the `ListInstanceIds` and
  `StreamInstanceHistory` operations.
- **No exported instances:** verify that the orchestrations reached a terminal status inside the completion-time
  window and that the runtime-status filter includes their status.
- **Storage authorization failure:** verify the Storage connection string and Blob container access.
- **Continuous job takes time to observe new work:** an idle continuous job checks again on its durable timer; the
  sample waits up to two minutes.