package com.sangam.ai.session;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Background worker that consumes AI jobs from the Redis queue.
 *
 * Runs a single background thread that blocks on queue.take().
 * When a job arrives, it hands it to AiStreamingService for processing.
 * When processing finishes, it immediately loops back to wait for the next job.
 *
 * The thread is started on application startup (@PostConstruct)
 * and gracefully stopped on shutdown (@PreDestroy).
 *
 * To scale: run multiple instances of the application.
 * Each instance runs one worker thread, all consuming the same Redis queue.
 * Redisson ensures each job is delivered to exactly one worker.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiJobWorker {

    private final AiJobQueue jobQueue;
    private final AiStreamingService aiStreamingService;

    private volatile boolean running = true;
    private Thread workerThread;

    @PostConstruct
    public void start() {
        workerThread = new Thread(this::processLoop, "ai-job-worker");
        // Daemon thread — JVM won't wait for it when shutting down
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("AI job worker started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("AI job worker stopped");
    }

    private void processLoop() {
        while (running) {
            try {
                // Blocks here until a job is available
                AiJob job = jobQueue.take();
                log.info("Processing AI job for node {} type {}",
                        job.nodeId(), job.type());

                // Process the job — this runs the full streaming pipeline
                aiStreamingService.process(job);

            } catch (InterruptedException e) {
                // Interrupted during take() — this is the shutdown signal
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Don't let an error in one job kill the worker loop.
                // Log and keep going — the next job will still be processed.
                log.error("Error processing AI job: {}", e.getMessage(), e);
            }
        }
    }
}