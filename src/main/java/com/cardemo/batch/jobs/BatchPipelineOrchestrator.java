package com.cardemo.batch.jobs;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cardemo.config.AwsConfig;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

/**
 * 5-Stage Nightly Batch Pipeline Orchestrator.
 *
 * <p>Migrated from the mainframe JCL pipeline sequence:
 * <pre>
 *   POSTTRAN.jcl → INTCALC.jcl → COMBTRAN.jcl → CREASTMT.JCL / TRANREPT.jcl
 * </pre>
 *
 * <p>Pipeline stages:
 * <ul>
 *   <li>Stage 1 (POSTTRAN): Daily Transaction Posting — validates and posts daily transactions</li>
 *   <li>Stage 2 (INTCALC): Interest Calculation — computes monthly interest on category balances</li>
 *   <li>Stage 3 (COMBTRAN): Combine Transactions — sorts and merges transaction files</li>
 *   <li>Stage 4a (CREASTMT): Statement Generation — generates text + HTML statements (PARALLEL)</li>
 *   <li>Stage 4b (TRANREPT): Transaction Report — generates date-filtered reports (PARALLEL)</li>
 * </ul>
 *
 * <p>Sequential dependency: Stage N+1 starts ONLY after Stage N completes successfully.
 * Stages 4a and 4b execute in PARALLEL after Stage 3 completes — they have no data
 * dependencies on each other (both read from TRANSACT after Stage 3 loads it).
 *
 * <p>JCL COND code logic:
 * <ul>
 *   <li>POSTTRAN RETURN-CODE 0 (COMPLETED) — all records posted → downstream stages proceed</li>
 *   <li>POSTTRAN RETURN-CODE 4 (COMPLETED_WITH_REJECTS) — partial rejections → downstream stages proceed
 *       (CBTRN02C.cbl line 230: MOVE 4 TO RETURN-CODE when WS-REJECT-COUNT &gt; 0)</li>
 *   <li>POSTTRAN RETURN-CODE &gt;= 8 (FAILED) — fatal error → pipeline stops immediately</li>
 * </ul>
 */
@Configuration
@Profile("batch")
public class BatchPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BatchPipelineOrchestrator.class);

    /**
     * Decider status: pipeline should continue to the next stage.
     * Maps to JCL RETURN-CODE 0 (all records posted) or RETURN-CODE 4 (partial rejections).
     */
    private static final String DECIDER_STATUS_CONTINUE = "CONTINUE";

    /**
     * Decider status: pipeline should stop due to fatal error.
     * Maps to JCL RETURN-CODE &gt;= 8 (fatal error in POSTTRAN step).
     */
    private static final String DECIDER_STATUS_FAILED = "FAILED";

    /**
     * Defines the 5-stage nightly batch pipeline meta-job.
     *
     * <p>Wires all 5 individual batch job steps into a single orchestrated pipeline:
     * <pre>
     *   Stage 1 → Condition Code Decider → CONTINUE → Stage 2 → Stage 3 → [Stage 4a ‖ Stage 4b]
     *                                    → FAILED  → pipeline stops
     * </pre>
     *
     * @param jobRepository    Spring Batch job metadata repository
     * @param postingStep      Stage 1: Daily Transaction Posting (POSTTRAN / CBTRN02C)
     * @param interestStep     Stage 2: Interest Calculation (INTCALC / CBACT04C)
     * @param combineStep      Stage 3: Combine Transactions (COMBTRAN / DFSORT+REPRO)
     * @param statementStep    Stage 4a: Statement Generation (CREASTMT / CBSTM03A+B) — parallel
     * @param reportStep       Stage 4b: Transaction Report (TRANREPT / CBTRN03C) — parallel
     * @param pipelineListener Job lifecycle listener wired separately so Spring can
     *                         inject its own dependencies (SnsClient, AwsConfig) —
     *                         see {@link #pipelineListener(SnsClient, AwsConfig)}
     * @return the fully wired 5-stage pipeline Job
     */
    @Bean("batchPipelineJob")
    public Job batchPipelineJob(
            JobRepository jobRepository,
            @Qualifier("dailyTransactionPostingStep") Step postingStep,
            @Qualifier("interestCalculationStep") Step interestStep,
            @Qualifier("combineTransactionsStep") Step combineStep,
            @Qualifier("statementGenerationStep") Step statementStep,
            @Qualifier("transactionReportStep") Step reportStep,
            JobExecutionListener pipelineListener) {

        // Stage 1 wrapped in Flow — required for JobBuilder.start(Flow) to enable
        // flow-based pipeline with deciders and parallel execution
        Flow stage1Flow = new FlowBuilder<Flow>("stage1-posttran")
                .start(postingStep)
                .build();

        // Stages 4a and 4b run in PARALLEL using FlowBuilder.split()
        // Per AAP §0.8.5: CREASTMT and TRANREPT have no data dependencies on each other.
        // Both read from TRANSACT after Stage 3 loads it — parallel execution is safe.
        Flow stage4aFlow = new FlowBuilder<Flow>("stage4a-creastmt")
                .start(statementStep)
                .build();

        Flow stage4bFlow = new FlowBuilder<Flow>("stage4b-tranrept")
                .start(reportStep)
                .build();

        Flow parallelStage4 = new FlowBuilder<Flow>("stage4-parallel")
                .split(batchPipelineTaskExecutor())
                .add(stage4aFlow, stage4bFlow)
                .build();

        // Wire the 5-stage sequential pipeline with JCL COND code logic
        //
        // Pipeline flow:
        //   Stage1(POSTTRAN) → DecisionPoint → CONTINUE → Stage2(INTCALC)
        //                                    → FAILED   → stop
        //   Stage2(INTCALC) → Stage3(COMBTRAN) → [Stage4a(CREASTMT) ‖ Stage4b(TRANREPT)]
        return new JobBuilder("batchPipelineJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(pipelineListener)
                .start(stage1Flow)
                .next(conditionCodeDecider())
                .on(DECIDER_STATUS_CONTINUE).to(interestStep)
                .from(conditionCodeDecider()).on(DECIDER_STATUS_FAILED).fail()
                .from(interestStep).next(combineStep)
                .on("COMPLETED").to(parallelStage4)
                .end()
                .build();
    }

    /**
     * JCL COND code decision logic for the batch pipeline.
     *
     * <p>Evaluates the POSTTRAN (Stage 1) step exit status to determine whether downstream
     * stages should proceed:
     * <ul>
     *   <li>RETURN-CODE = 0 (ExitStatus.COMPLETED): All daily transactions posted → CONTINUE</li>
     *   <li>RETURN-CODE = 4 (ExitStatus "COMPLETED_WITH_REJECTS"): Some rejections but processing
     *       continues — rejected transactions are excluded from the TRANSACT file, so interest
     *       calculation, transaction combining, and reporting operate on posted transactions only → CONTINUE
     *       (CBTRN02C.cbl line 230: MOVE 4 TO RETURN-CODE when WS-REJECT-COUNT &gt; 0)</li>
     *   <li>RETURN-CODE &gt;= 8 (ExitStatus.FAILED): Fatal error — stop pipeline → FAILED</li>
     * </ul>
     *
     * @return a JobExecutionDecider implementing JCL COND code semantics
     */
    @Bean
    public JobExecutionDecider conditionCodeDecider() {
        return (JobExecution jobExecution, StepExecution stepExecution) -> {
            // The decider is invoked after Stage 1 (POSTTRAN) which is wrapped in a Flow.
            // When a step is wrapped inside a FlowBuilder, the stepExecution parameter
            // passed to the decider may be null. We must retrieve the actual POSTTRAN
            // step execution from the JobExecution context by name.
            StepExecution postingStepExecution = jobExecution.getStepExecutions().stream()
                    .filter(se -> "dailyTransactionPostingStep".equals(se.getStepName()))
                    .findFirst()
                    .orElse(stepExecution);

            if (postingStepExecution == null) {
                // Defensive: if neither jobExecution lookup nor parameter yields a step,
                // treat as fatal — do NOT silently continue through potentially corrupt pipeline
                log.error("Pipeline decider: POSTTRAN step execution not found in job context — "
                        + "halting pipeline as a safety measure");
                return FlowExecutionStatus.FAILED;
            }

            String exitCode = postingStepExecution.getExitStatus().getExitCode();

            // JCL COND code mapping:
            // Only FAILED exit status stops the pipeline (maps to RETURN-CODE >= 8).
            // COMPLETED (RC=0) and COMPLETED_WITH_REJECTS (RC=4) both allow downstream stages.
            // Any other non-FAILED status also allows continuation for robustness.
            boolean isFatalFailure = "FAILED".equals(exitCode);
            String decision = isFatalFailure ? DECIDER_STATUS_FAILED : DECIDER_STATUS_CONTINUE;

            log.info("Pipeline decider: POSTTRAN exit status={}, proceeding={}", exitCode, decision);

            if (isFatalFailure) {
                return FlowExecutionStatus.FAILED;
            }
            return new FlowExecutionStatus(DECIDER_STATUS_CONTINUE);
        };
    }

    /**
     * TaskExecutor for parallel execution of Pipeline Stages 4a and 4b.
     *
     * <p>Per AAP §0.8.5, CREASTMT (statement generation) and TRANREPT (transaction report)
     * may execute in parallel after COMBTRAN (Stage 3) completes. They have no data
     * dependencies on each other — both read from the same TRANSACT dataset loaded by Stage 3.
     *
     * <p>Thread prefix "batch-pipeline-" aids observability in structured logs, enabling
     * correlation of log entries across parallel stage threads via logstash-logback-encoder.
     *
     * @return a SimpleAsyncTaskExecutor with the "batch-pipeline-" thread prefix
     */
    @Bean
    public TaskExecutor batchPipelineTaskExecutor() {
        return new SimpleAsyncTaskExecutor("batch-pipeline-");
    }

    /**
     * Pipeline lifecycle listener for structured logging, performance metrics, and
     * SNS-based failure alerting.
     *
     * <p>Logs pipeline start and completion events with timestamps for Gate 3
     * (Performance Baseline) evidence. Captures failure details for diagnostic purposes.
     * All log messages flow through logstash-logback-encoder for structured JSON output
     * with correlation IDs and trace context per AAP §0.7.1 observability requirements.
     *
     * <p><strong>SNS Alerting (AAP §0.3.1):</strong> When the pipeline completes with
     * {@link BatchStatus#FAILED}, {@code afterJob()} publishes a structured alert message
     * to the {@code carddemo-batch-alerts} SNS topic (configurable via
     * {@code carddemo.aws.sns.batch-alerts-topic}). The topic ARN is resolved idempotently
     * via {@link SnsClient#createTopic(CreateTopicRequest)} — this returns the existing
     * ARN if the topic already exists, or creates it otherwise, matching standard AWS
     * alerting patterns. SNS failures are caught and logged; they never propagate to the
     * batch job status, preserving the integrity of the Spring Batch exit code per the
     * minimal change clause (AAP §0.8.1 R-001).
     *
     * <p>Success paths (COMPLETED, STOPPED) emit structured log entries only and do not
     * publish to SNS, keeping the SNS topic signal-focused on actionable alerts.
     *
     * @param snsClient SNS client bean from {@link AwsConfig#snsClient()} used to publish
     *                  pipeline-failure alerts
     * @param awsConfig Configuration holder providing {@link AwsConfig#getBatchAlertsTopic()}
     *                  for topic name resolution (default: {@code carddemo-batch-alerts})
     * @return a JobExecutionListener for pipeline lifecycle logging and failure alerting
     */
    @Bean
    public JobExecutionListener pipelineListener(SnsClient snsClient, AwsConfig awsConfig) {
        return new JobExecutionListener() {

            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("Starting CardDemo nightly batch pipeline — 5 stages");
                Long instanceId = jobExecution.getJobInstance() != null
                        ? jobExecution.getJobInstance().getInstanceId() : null;
                log.info("Pipeline job instance ID: {}, execution ID: {}, parameters: {}",
                        instanceId,
                        jobExecution.getId(),
                        jobExecution.getJobParameters());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                log.info("CardDemo batch pipeline complete — status={}, startTime={}, endTime={}",
                        jobExecution.getStatus(),
                        jobExecution.getStartTime(),
                        jobExecution.getEndTime());

                // Log any failure exceptions encountered during the pipeline run
                if (jobExecution.getAllFailureExceptions() != null
                        && !jobExecution.getAllFailureExceptions().isEmpty()) {
                    log.error("Pipeline encountered {} failure(s):",
                            jobExecution.getAllFailureExceptions().size());
                    for (Throwable ex : jobExecution.getAllFailureExceptions()) {
                        log.error("Pipeline failure detail: {}", ex.getMessage(), ex);
                    }
                }

                // AAP §0.3.1 — SNS alert notifications for batch pipeline FAILED status.
                // Publishes a structured alert to the carddemo-batch-alerts topic so that
                // operators can detect overnight pipeline failures without tailing logs.
                // Only publishes on FAILED to keep SNS signal-focused on actionable alerts.
                if (jobExecution.getStatus() == BatchStatus.FAILED) {
                    publishFailureAlert(snsClient, awsConfig, jobExecution);
                }
            }
        };
    }

    /**
     * Publishes a pipeline-failure alert to the configured SNS topic.
     *
     * <p>Resolves the topic ARN idempotently via {@code createTopic} (AWS-documented
     * behavior: returns existing ARN if topic already exists, otherwise creates it),
     * then publishes a short subject line and a multi-line message body summarizing
     * the failure. All SNS exceptions are caught and logged at ERROR level; they never
     * propagate out of this method. This ensures that an alerting outage does not
     * change the Spring Batch job exit status, which would violate the behavior-parity
     * rule (AAP §0.8.1 R-001) — batch pipeline exit codes are externally observable
     * contracts used by JCL-equivalent orchestration.
     *
     * <p>The subject is bounded to stay within AWS's 100-character SNS subject limit:
     * {@code "[CardDemo Batch] Pipeline FAILED (ExecutionId=<id>)"}.
     *
     * @param snsClient    the SNS client to use for the publish call
     * @param awsConfig    configuration holder for the topic name lookup
     * @param jobExecution the failed job execution to summarize in the alert
     */
    private void publishFailureAlert(SnsClient snsClient,
                                     AwsConfig awsConfig,
                                     JobExecution jobExecution) {
        String topicName = awsConfig.getBatchAlertsTopic();
        try {
            // createTopic is idempotent in AWS SNS — returns existing ARN if the topic
            // already exists; otherwise creates it. This avoids the need to cache ARNs
            // or fail if LocalStack / fresh environments lack pre-provisioned topics.
            CreateTopicResponse topicResponse = snsClient.createTopic(
                    CreateTopicRequest.builder().name(topicName).build());
            String topicArn = topicResponse.topicArn();

            String subject = buildFailureAlertSubject(jobExecution);
            String message = buildFailureAlertMessage(jobExecution);

            PublishResponse publishResponse = snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject(subject)
                    .message(message)
                    .build());

            log.info("Published batch-failure alert to SNS — topic={}, messageId={}",
                    topicName, publishResponse.messageId());
        } catch (SnsException ex) {
            // Never propagate SNS alerting failures — they must not alter batch job status.
            log.error("Failed to publish batch-failure SNS alert (topic={}): {}",
                    topicName, ex.getMessage(), ex);
        }
    }

    /**
     * Builds the SNS subject line for a batch-failure alert.
     *
     * <p>Kept under AWS's 100-character SNS subject limit. Format:
     * {@code "[CardDemo Batch] Pipeline FAILED (ExecutionId=<id>)"}.
     *
     * @param jobExecution the failed job execution
     * @return a subject string suitable for SNS
     */
    private String buildFailureAlertSubject(JobExecution jobExecution) {
        return "[CardDemo Batch] Pipeline FAILED (ExecutionId=" + jobExecution.getId() + ")";
    }

    /**
     * Builds the multi-line SNS message body summarizing a batch-failure event.
     *
     * <p>Includes status, execution ID, job instance ID, start/end timestamps, and
     * a compact listing of all failure exceptions with their class names and messages.
     *
     * @param jobExecution the failed job execution
     * @return a multi-line message string summarizing the failure
     */
    private String buildFailureAlertMessage(JobExecution jobExecution) {
        StringBuilder message = new StringBuilder(512);
        message.append("CardDemo nightly batch pipeline failed.\n\n");
        message.append("Status:       ").append(jobExecution.getStatus()).append('\n');
        message.append("ExecutionId:  ").append(jobExecution.getId()).append('\n');
        Long instanceId = jobExecution.getJobInstance() != null
                ? jobExecution.getJobInstance().getInstanceId() : null;
        message.append("InstanceId:   ").append(instanceId).append('\n');
        message.append("StartTime:    ").append(jobExecution.getStartTime()).append('\n');
        message.append("EndTime:      ").append(jobExecution.getEndTime()).append('\n');
        message.append("ExitStatus:   ").append(jobExecution.getExitStatus()).append('\n');

        if (jobExecution.getAllFailureExceptions() != null
                && !jobExecution.getAllFailureExceptions().isEmpty()) {
            message.append("\nFailures (")
                    .append(jobExecution.getAllFailureExceptions().size())
                    .append("):\n");
            int index = 1;
            for (Throwable ex : jobExecution.getAllFailureExceptions()) {
                message.append("  ").append(index++).append(". ")
                        .append(ex.getClass().getSimpleName())
                        .append(": ")
                        .append(ex.getMessage())
                        .append('\n');
            }
        } else {
            message.append("\nNo failure exceptions captured on JobExecution.\n");
        }

        return message.toString();
    }
}
