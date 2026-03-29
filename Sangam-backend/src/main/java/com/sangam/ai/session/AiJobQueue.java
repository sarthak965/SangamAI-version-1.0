package com.sangam.ai.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiJobQueue {

    private static final String QUEUE_NAME = "sangamai:ai-jobs";

    private final RedissonClient redissonClient;

    private RBlockingQueue<AiJob> getQueue() {
        return redissonClient.getBlockingQueue(QUEUE_NAME);
    }

    /**
     * Push a job onto the queue.
     * Called by SessionService immediately after creating a node.
     * Returns instantly — does not wait for the job to be processed.
     */
    public void enqueue(AiJob job) {
        try {
            getQueue().offer(job);
            log.info("Enqueued AI job for node {} type {}",
                    job.nodeId(), job.type());
        } catch (Exception e) {
            log.error("Failed to enqueue AI job for node {}: {}",
                    job.nodeId(), e.getMessage());
            throw new RuntimeException("Failed to queue AI job", e);
        }
    }

    /**
     * Take the next job from the queue.
     * BLOCKS the calling thread until a job is available.
     * This is exactly what we want in the worker — sit and wait
     * efficiently rather than polling in a busy loop.
     */
    public AiJob take() throws InterruptedException {
        return getQueue().take();
    }
}