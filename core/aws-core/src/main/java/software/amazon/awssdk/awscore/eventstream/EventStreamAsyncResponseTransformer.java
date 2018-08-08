/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.awscore.eventstream;

import static software.amazon.awssdk.utils.FunctionalUtils.runAndLogError;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.ReviewBeforeRelease;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.http.HttpResponse;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.internal.util.ThrowableUtils;
import software.amazon.awssdk.utils.BinaryUtils;
import software.amazon.eventstream.Message;
import software.amazon.eventstream.MessageDecoder;

/**
 * Unmarshalling layer on top of the {@link AsyncResponseTransformer} to decode event stream messages and deliver them to the
 * subscriber.
 *
 * @param <ResponseT> Initial response type of event stream operation.
 * @param <EventT> Base type of event stream message frames.
 */
@SdkProtectedApi
public class EventStreamAsyncResponseTransformer<ResponseT, EventT>
    implements AsyncResponseTransformer<SdkResponse, Void> {

    private static final Logger log = LoggerFactory.getLogger(EventStreamAsyncResponseTransformer.class);

    private static final Object ON_COMPLETE_EVENT = new Object();

    private static final ExecutionAttributes EMPTY_EXECUTION_ATTRIBUTES = new ExecutionAttributes();

    /**
     * {@link EventStreamResponseHandler} provided by customer.
     */
    private final EventStreamResponseHandler<ResponseT, EventT> eventStreamResponseTransformer;

    /**
     * Unmarshalls the initial response.
     */
    private final HttpResponseHandler<? extends ResponseT> initialResponseUnmarshaller;

    /**
     * Unmarshalls the event POJO.
     */
    private final HttpResponseHandler<? extends EventT> eventUnmarshaller;

    /**
     * Unmarshalls exception events.
     */
    private final HttpResponseHandler<? extends Throwable> exceptionUnmarshaller;

    /**
     * Remaining demand (i.e number of unmarshalled events) we need to provide to the customers subscriber.
     */
    private final AtomicLong remainingDemand = new AtomicLong(0);

    /**
     * Reference to customers subscriber to events.
     */
    private final AtomicReference<Subscriber<? super EventT>> subscriberRef = new AtomicReference<>();

    private final AtomicReference<Subscription> dataSubscription = new AtomicReference<>();

    /**
     * Event stream message decoder that decodes the binary data into "frames". These frames are then passed to the
     * unmarshaller to produce the event POJO.
     */
    private final MessageDecoder decoder = new MessageDecoder(this::handleMessage);

    /**
     * Tracks whether we have delivered a terminal notification to the subscriber and response handler
     * (i.e. exception or completion).
     */
    private volatile boolean isDone = false;

    /**
     * Holds a reference to any exception delivered to exceptionOccurred.
     */
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    /**
     * Executor to deliver events to the subscriber
     */
    private final Executor executor;

    /**
     * Queue of events to deliver to downstream subscriber. Will contain mostly objects
     * of type EventT, the special {@link #ON_COMPLETE_EVENT} will be added when all events
     * have been added to the queue.
     */
    private final Queue<Object> eventsToDeliver = new LinkedList<>();

    /**
     * Flag to indicate we are currently delivering events to the subscriber.
     */
    private final AtomicBoolean isDelivering = new AtomicBoolean(false);

    /**
     * Flag to indicate we are currently requesting demand from the data publisher.
     */
    private final AtomicBoolean isRequesting = new AtomicBoolean(false);

    /**
     * Future to notify on completion. Note that we do not notify this future in the event of an error, that
     * is handled separately by the generated client. Ultimately we need this due to a disconnect between
     * completion of the request (i.e. finish reading all the data from the wire) and the completion of the event
     * stream (i.e. deliver the last event to the subscriber).
     */
    private final CompletableFuture<Void> future;

    @Deprecated
    @ReviewBeforeRelease("Remove this on full GA of 2.0.0")
    public EventStreamAsyncResponseTransformer(
        EventStreamResponseHandler<ResponseT, EventT> eventStreamResponseTransformer,
        HttpResponseHandler<? extends ResponseT> initialResponseUnmarshaller,
        HttpResponseHandler<? extends EventT> eventUnmarshaller,
        HttpResponseHandler<? extends Throwable> exceptionUnmarshaller) {
        this(eventStreamResponseTransformer, initialResponseUnmarshaller, eventUnmarshaller, exceptionUnmarshaller,
             Executors.newSingleThreadScheduledExecutor(), new CompletableFuture<>());
    }

    private EventStreamAsyncResponseTransformer(
        EventStreamResponseHandler<ResponseT, EventT> eventStreamResponseTransformer,
        HttpResponseHandler<? extends ResponseT> initialResponseUnmarshaller,
        HttpResponseHandler<? extends EventT> eventUnmarshaller,
        HttpResponseHandler<? extends Throwable> exceptionUnmarshaller,
        Executor executor,
        CompletableFuture<Void> future) {

        this.eventStreamResponseTransformer = eventStreamResponseTransformer;
        this.initialResponseUnmarshaller = initialResponseUnmarshaller;
        this.eventUnmarshaller = eventUnmarshaller;
        this.exceptionUnmarshaller = exceptionUnmarshaller;
        this.executor = executor;
        this.future = future;
    }

    @Override
    public void responseReceived(SdkResponse response) {
        // We use a void unmarshaller and unmarshall the actual response in the message
        // decoder when we receive the initial-response frame. TODO not clear
        // how we would handle REST protocol which would unmarshall the response from the HTTP headers
    }

    @Override
    public void onStream(SdkPublisher<ByteBuffer> publisher) {
        synchronized (this) {
            // Reset to allow more exceptions to propagate for retries
            isDone = false;
        }
        CompletableFuture<Subscription> dataSubscriptionFuture = new CompletableFuture<>();
        publisher.subscribe(new ByteSubscriber(dataSubscriptionFuture));
        dataSubscriptionFuture.thenAccept(dataSubscription -> {
            SdkPublisher<EventT> eventPublisher = new EventPublisher(dataSubscription);
            try {
                eventStreamResponseTransformer.onEventStream(eventPublisher);
            } catch (Throwable t) {
                exceptionOccurred(t);
                dataSubscription.cancel();
            }
        });
    }

    @Override
    public void exceptionOccurred(Throwable throwable) {
        synchronized (this) {
            if (!isDone) {
                isDone = true;
                error.set(throwable);
                // If we have a Subscriber at this point notify it as well
                if (subscriberRef.get() != null) {
                    runAndLogError(log, "Error thrown from Subscriber#onError, ignoring.",
                                   () -> subscriberRef.get().onError(throwable));
                }
                eventStreamResponseTransformer.exceptionOccurred(throwable);
            }
        }
    }

    @Override
    public Void complete() {
        if (error.get() == null) {
            // Add the special on complete event to signal drainEvents to complete the subscriber
            eventsToDeliver.add(ON_COMPLETE_EVENT);
            drainEventsIfNotAlready();
            return null;
        } else {
            // Need to propagate the failure up so the future is completed exceptionally. This should only happen
            // when there is a frame level exception that the upper layers don't know about.
            throw ThrowableUtils.failure(error.get());
        }
    }

    /**
     * Called when all events have been delivered to the downstream subscriber.
     */
    private void onEventComplete() {
        synchronized (this) {
            isDone = true;
            runAndLogError(log, "Error thrown from Subscriber#onComplete, ignoring.",
                           () -> subscriberRef.get().onComplete());
            eventStreamResponseTransformer.complete();
            future.complete(null);
        }
    }

    /**
     * Handle the event stream message according to it's type.
     *
     * @param m Decoded message.
     */
    private void handleMessage(Message m) {
        try {
            // TODO: Can we move all of the dispatching to a single unmarshaller?
            if (isEvent(m)) {
                if (m.getHeaders().get(":event-type").getString().equals("initial-response")) {
                    eventStreamResponseTransformer.responseReceived(
                        initialResponseUnmarshaller.handle(adaptMessageToResponse(m),
                                                           EMPTY_EXECUTION_ATTRIBUTES));
                } else {
                    // Add to queue to be delivered later by the executor
                    eventsToDeliver.add(eventUnmarshaller.handle(adaptMessageToResponse(m),
                                                                 EMPTY_EXECUTION_ATTRIBUTES));
                }
            } else if (isError(m) || isException(m)) {
                Throwable exception = exceptionUnmarshaller.handle(adaptMessageToResponse(m), EMPTY_EXECUTION_ATTRIBUTES);
                runAndLogError(log, "Error thrown from exceptionOccurred, ignoring.", () -> exceptionOccurred(exception));
            }
        } catch (Exception e) {
            throw SdkClientException.builder().cause(e).build();
        }
    }

    /**
     * @param m Message frame.
     * @return True if frame is an event frame, false if not.
     */
    private boolean isEvent(Message m) {
        return "event".equals(m.getHeaders().get(":message-type").getString());
    }

    /**
     * @param m Message frame.
     * @return True if frame is an error frame, false if not.
     */
    private boolean isError(Message m) {
        return "error".equals(m.getHeaders().get(":message-type").getString());
    }

    /**
     * @param m Message frame.
     * @return True if frame is an exception frame, false if not.
     */
    private boolean isException(Message m) {
        return "exception".equals(m.getHeaders().get(":message-type").getString());
    }

    /**
     * Transforms an event stream message into a {@link HttpResponse} so we can reuse our existing generated unmarshallers.
     *
     * @param m Message to transform.
     */
    private HttpResponse adaptMessageToResponse(Message m) {
        HttpResponse response = new HttpResponse(null);
        response.setContent(new ByteArrayInputStream(m.getPayload()));
        // TODO we'll probably need to handle other typed headers at some point
        m.getHeaders().forEach((k, v) -> response.addHeader(k, v.getString()));
        return response;
    }

    /**
     * Subscriber for the raw bytes from the stream. Feeds them to the {@link MessageDecoder} as they arrive
     * and will request as much as needed to fulfill any outstanding demand.
     */
    private class ByteSubscriber implements Subscriber<ByteBuffer> {

        private final CompletableFuture<Subscription> dataSubscriptionFuture;

        /**
         * @param dataSubscriptionFuture Future to notify when the {@link Subscription} object is available.
         */
        private ByteSubscriber(CompletableFuture<Subscription> dataSubscriptionFuture) {
            this.dataSubscriptionFuture = dataSubscriptionFuture;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            dataSubscription.set(subscription);
            dataSubscriptionFuture.complete(subscription);
        }

        @Override
        public void onNext(ByteBuffer buffer) {
            // Bail out if we've already delivered an exception to the downstream subscriber
            if (isDone) {
                return;
            }
            synchronized (eventsToDeliver) {
                decoder.feed(BinaryUtils.copyBytesFrom(buffer));
                // If we have things to deliver, do so.
                if (!eventsToDeliver.isEmpty()) {
                    isRequesting.compareAndSet(true, false);
                    drainEventsIfNotAlready();
                } else {
                    // If we still haven't fulfilled the outstanding demand then keep requesting byte chunks until we do
                    if (remainingDemand.get() > 0) {
                        dataSubscription.get().request(1);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // Notified in response handler exceptionOccurred because we have more context on what we've delivered to
            // the event stream subscriber there.
        }

        @Override
        public void onComplete() {
            // Notified in onEventComplete method because we have more context on what we've delivered to
            // the event stream subscriber there.
        }
    }

    /**
     * Publisher of event stream events. Tracks outstanding demand and requests raw data from the stream until that demand is
     * fulfilled.
     */
    private class EventPublisher implements SdkPublisher<EventT> {

        private final Subscription dataSubscription;

        private EventPublisher(Subscription dataSubscription) {
            this.dataSubscription = dataSubscription;
        }

        @Override
        public void subscribe(Subscriber<? super EventT> subscriber) {
            if (subscriberRef.compareAndSet(null, subscriber)) {
                subscriber.onSubscribe(new Subscription() {
                    @Override
                    public void request(long l) {
                        if (isDone) {
                            return;
                        }
                        synchronized (eventsToDeliver) {
                            remainingDemand.addAndGet(l);
                            if (!eventsToDeliver.isEmpty()) {
                                drainEventsIfNotAlready();
                            } else {
                                requestDataIfNotAlready();
                            }
                        }
                    }

                    @Override
                    public void cancel() {
                        dataSubscription.cancel();
                    }
                });
            } else {
                log.error("Event stream publishers can only be subscribed to once.");
                throw new IllegalStateException("This publisher may only be subscribed to once");
            }
        }
    }

    /**
     * Requests data from the {@link ByteBuffer} {@link Publisher} until we have enough data to fulfill demand. If we are
     * already requesting data this is a no-op.
     */
    private void requestDataIfNotAlready() {
        if (isRequesting.compareAndSet(false, true)) {
            dataSubscription.get().request(1);
        }
    }

    /**
     * Drains events from the queue until the demand is met or all events are delivered. If we are already
     * in the process of delivering events this is a no-op.
     */
    private void drainEventsIfNotAlready() {
        if (isDelivering.compareAndSet(false, true)) {
            drainEvents();
        }
    }

    /**
     * Drains events from the queue until the demand is met or all events are delivered. This differs
     * from {@link #drainEventsIfNotAlready()} in that it assumes it has the {@link #isDelivering} 'lease' already.
     */
    private void drainEvents() {
        // If we've already delivered an exception to the subscriber than bail out
        if (isDone) {
            return;
        }
        synchronized (eventsToDeliver) {
            if (eventsToDeliver.peek() == ON_COMPLETE_EVENT) {
                onEventComplete();
                return;
            }
            if (eventsToDeliver.isEmpty() || remainingDemand.get() == 0) {
                isDelivering.compareAndSet(true, false);
                // If we still have demand to fulfill then request more if we aren't already requesting
                if (remainingDemand.get() > 0) {
                    requestDataIfNotAlready();
                }
            } else {
                // Deliver the event and recursively call ourselves after it's delivered
                Object event = eventsToDeliver.remove();
                remainingDemand.decrementAndGet();
                CompletableFuture.runAsync(() -> deliverEvent(event), executor)
                                 .thenRunAsync(this::drainEvents, executor);
            }
        }
    }

    /**
     * Delivers the event to the downstream subscriber. We already know the type so the cast is safe.
     */
    @SuppressWarnings("unchecked")
    private void deliverEvent(Object event) {
        subscriberRef.get().onNext((EventT) event);
    }

    /**
     * Creates a {@link Builder} used to create {@link EventStreamAsyncResponseTransformer}.
     *
     * @param <ResponseT> Initial response type.
     * @param <EventT> Event type being delivered.
     * @return New {@link Builder} instance.
     */
    public static <ResponseT, EventT> Builder<ResponseT, EventT> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link EventStreamAsyncResponseTransformer}.
     *
     * @param <ResponseT> Initial response type.
     * @param <EventT> Event type being delivered.
     */
    public static final class Builder<ResponseT, EventT> {

        private EventStreamResponseHandler<ResponseT, EventT> eventStreamResponseTransformer;
        private HttpResponseHandler<? extends ResponseT> initialResponseUnmarshaller;
        private HttpResponseHandler<? extends EventT> eventUnmarshaller;
        private HttpResponseHandler<? extends Throwable> exceptionUnmarshaller;
        private Executor executor;
        private CompletableFuture<Void> future;

        private Builder() {
        }

        /**
         * @param eventStreamResponseTransformer Response transformer provided by customer.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> eventStreamResponseTransformer(EventStreamResponseHandler<ResponseT, EventT> eventStreamResponseTransformer) {
            this.eventStreamResponseTransformer = eventStreamResponseTransformer;
            return this;
        }

        /**
         * @param initialResponseUnmarshaller Unmarshaller for the initial-response event stream message.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> initialResponseUnmarshaller(HttpResponseHandler<? extends ResponseT> initialResponseUnmarshaller) {
            this.initialResponseUnmarshaller = initialResponseUnmarshaller;
            return this;
        }

        /**
         * @param eventUnmarshaller Unmarshaller for the various event types.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> eventUnmarshaller(HttpResponseHandler<? extends EventT> eventUnmarshaller) {
            this.eventUnmarshaller = eventUnmarshaller;
            return this;
        }

        /**
         * @param exceptionUnmarshaller Unmarshaller for error and exception messages.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> exceptionUnmarshaller(HttpResponseHandler<? extends Throwable> exceptionUnmarshaller) {
            this.exceptionUnmarshaller = exceptionUnmarshaller;
            return this;
        }

        /**
         * @param executor Executor used to deliver events.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * @param future Future to notify when the last event has been delivered.
         * @return This object for method chaining.
         */
        public Builder<ResponseT, EventT> future(CompletableFuture<Void> future) {
            this.future = future;
            return this;
        }

        public EventStreamAsyncResponseTransformer<ResponseT, EventT> build() {
            return new EventStreamAsyncResponseTransformer<>(eventStreamResponseTransformer,
                                                             initialResponseUnmarshaller,
                                                             eventUnmarshaller,
                                                             exceptionUnmarshaller,
                                                             executor,
                                                             future);
        }
    }

}
