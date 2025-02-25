package org.zalando.nakadi.service.publishing;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class EventsProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(EventsProcessor.class);

    private final EventPublisher eventPublisher;
    private final ExecutorService executorService;

    private final long batchCollectionTimeout;
    private final int maxBatchSize;

    private final BlockingQueue<EventToPublish> eventsQueue;
    private final Thread dispatcherThread;

    private static class EventToPublish {
        private final String eventType;
        private final JSONObject object;

        private EventToPublish(final String eventType, final JSONObject object) {
            this.eventType = eventType;
            this.object = object;
        }
    }

    @Autowired
    public EventsProcessor(final EventPublisher eventPublisher,
                           @Value("${nakadi.kpi.config.batch-collection-timeout}") final long batchCollectionTimeout,
                           @Value("${nakadi.kpi.config.batch-size}") final int maxBatchSize,
                           @Value("${nakadi.kpi.config.workers}") final int workers,
                           @Value("${nakadi.kpi.config.batch-queue:100}") final int maxBatchQueue,
                           @Value("${nakadi.kpi.config.events-queue-size}") final int eventsQueueSize) {
        this.eventPublisher = eventPublisher;
        this.batchCollectionTimeout = batchCollectionTimeout;
        this.maxBatchSize = maxBatchSize;

        // The following lines will create executor service of {@code workers} threads with burst up to workers * 2
        // threads, unused thread death timeout of 10 seconds,
        // maximum batch publishers in queue of {@code maxBatchQueue} and only logging rejection policy in case of
        // queue overflow.
        this.executorService = new ThreadPoolExecutor(
                workers,
                workers * 2,
                10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(maxBatchQueue),
                new NamedThreadFactory("internal-event-batch-sender-"),
                (runnable, threadPoolExecutor) -> {
                    LOG.warn("Failed publish batch {}, as batch publishing queue of size {} is full",
                            runnable, maxBatchQueue);
                }
        );
        this.eventsQueue = new ArrayBlockingQueue<>(eventsQueueSize);
        this.dispatcherThread = new Thread(this::dispatch, "processor-dispatch");
    }

    @PostConstruct
    public void start() {
        dispatcherThread.start();
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        dispatcherThread.interrupt();
        dispatcherThread.join();
        executorService.shutdown();
    }

    private static class BatchedRequest {
        private final String eventType;
        private final long finishCollectionAt;
        private final JSONArray data;
        private int size = 0;

        private BatchedRequest(final String eventType, final long finishCollectionAt) {
            this.eventType = eventType;
            this.finishCollectionAt = finishCollectionAt;
            this.data = new JSONArray();
        }

        public int add(final JSONObject obj) {
            this.data.put(obj);
            return ++size;
        }

        @Override
        public String toString() {
            return "Batch{et=" + eventType + ",size:" + size + "}";
        }
    }

    private void scheduleSendBatchedRequest(final BatchedRequest req) {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                sendEventsDisabledAuthz(req.data.toString(), req.eventType);
            }

            @Override
            public String toString() {
                return "Batch to " + req.eventType + " of size " + req.data.length();
            }
        };
        executorService.submit(r);
    }

    /**
     * Generates batches from {@link #eventsQueue} single events with the following constraints:
     * <ul>
     *     <li>Batch size is not more than {@link #maxBatchSize} events each</li>
     *     <li>Each batch is assembled for at most {@link #batchCollectionTimeout} ms (or close to it)</li>
     * </ul>
     */
    private void dispatch() {
        final Map<String, BatchedRequest> batchesBeingAssembled = new HashMap<>();
        try {
            // The time here is a moment in time at which batches should be evaluated - if they should be sent or not
            long nextTimeCheck = System.currentTimeMillis() + batchCollectionTimeout;
            while (true) {
                long currentTime = System.currentTimeMillis();
                final EventToPublish data = eventsQueue.poll(
                        Math.max(nextTimeCheck - currentTime, 1), TimeUnit.MILLISECONDS);
                currentTime = System.currentTimeMillis();
                boolean batchWasSent = false;
                // In case if data was taken - add it.
                if (data != null) {
                    BatchedRequest batch = batchesBeingAssembled.get(data.eventType);
                    if (null == batch) {
                        batch = new BatchedRequest(data.eventType, currentTime + batchCollectionTimeout);
                        batchesBeingAssembled.put(data.eventType, batch);
                    }
                    // In case if batch size is crossing maxBatchSize - send batch
                    if (batch.add(data.object) >= maxBatchSize) {
                        scheduleSendBatchedRequest(batch);
                        batchesBeingAssembled.remove(batch.eventType);
                        currentTime = System.currentTimeMillis();
                        batchWasSent = true;
                    }
                }
                if (batchWasSent || currentTime > nextTimeCheck) {
                    // In order to figure out when the batches should be sent - we are selecting the nearest time
                    // of batch expiration (finishCollectionAt) and use it in eventsQueue.poll(). Threr are only 2
                    // possible moments when this time is changing - a set of batches to send is changing or the time
                    // to send has come.
                    nextTimeCheck = currentTime + batchCollectionTimeout;
                    final Set<Map.Entry<String, BatchedRequest>> entries = batchesBeingAssembled.entrySet();
                    final Iterator<Map.Entry<String, BatchedRequest>> iterator = entries.iterator();
                    while (iterator.hasNext()){
                        final Map.Entry<String, BatchedRequest> entry = iterator.next();
                        if (entry.getValue().finishCollectionAt < currentTime) {
                            // If batch is to be sent
                            scheduleSendBatchedRequest(entry.getValue());
                            iterator.remove();
                        } else if (nextTimeCheck > entry.getValue().finishCollectionAt) {
                            // finishCollectionAt for the batch is closer then previous one
                            nextTimeCheck = entry.getValue().finishCollectionAt;
                        }
                    }
                }
            }
        } catch (InterruptedException ex) {
            LOG.info("Was interrupted while dispatching batches");
        }
        // now we have a lot of stuff to send
        sendLeftovesOnShutdown(batchesBeingAssembled);
    }

    private void sendLeftovesOnShutdown(final Map<String, BatchedRequest> batchesBeingAssembled) {
        EventToPublish taken;
        while (null != (taken = eventsQueue.poll())) {
            BatchedRequest batchedRequest = batchesBeingAssembled.get(taken.eventType);
            if (null == batchedRequest) {
                batchedRequest = new BatchedRequest(taken.eventType, System.currentTimeMillis());
                batchesBeingAssembled.put(taken.eventType, batchedRequest);
            }
            if (batchedRequest.add(taken.object) >= maxBatchSize) {
                scheduleSendBatchedRequest(batchedRequest);
                batchesBeingAssembled.remove(batchedRequest.eventType);
            }
        }
        for (final BatchedRequest req : batchesBeingAssembled.values()) {
            scheduleSendBatchedRequest(req);
        }
        batchesBeingAssembled.clear();
    }

    public void queueEvent(final String etName, final JSONObject event) {
        if (!eventsQueue.offer(new EventToPublish(etName, event))) {
            LOG.warn("Rejecting events to be queued for {} due to queue overload", etName);
        }
    }

    public void sendEventsDisabledAuthz(final String events, final String eventType) {
        try {
            // sending events batch with disabled authz check
            eventPublisher.processInternal(events, eventType, false, false);
        } catch (final RuntimeException ex) {
            LOG.error("Failed to send single batch for unknown reason", ex);
        }
    }

}
