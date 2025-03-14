package exchange.core2.collections.queue;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.lmax.disruptor.AlertException;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.util.ThreadHints;

/**
 * 纯正的disruptor
 */
public class DisruptorBlockingQueue<T> extends AbstractQueue<T> implements BlockingQueue<T> {
    /**
     * An event holder.
     */
    private static class Event<T> {
        private T item;

        public T removeValue() {
            T t = item;
            item = null;
            return t;
        }

        public T readValue() {
            return item;
        }

        public void setValue(T event) {
            this.item = event;
        }
    }

    /**
     * Event factory to create event holder instance.
     */
    private static class Factory<T> implements EventFactory<Event<T>> {

        @Override
        public Event<T> newInstance() {
            return new Event<T>();
        }

    }

    private final RingBuffer<Event<T>> ringBuffer;
    private final Sequence consumedSeq;
    private final SequenceBarrier barrier;
    private final HybridTimeoutBlockingWaitStrategy timeoutBlockingWaitStrategy = new HybridTimeoutBlockingWaitStrategy();
    private long knownPublishedSeq;

    public DisruptorBlockingQueue(int bufferSize) {
        this(bufferSize, true);
    }

    public DisruptorBlockingQueue(int bufferSize, boolean singleProducer) {
        if (singleProducer) {
            ringBuffer = RingBuffer.createSingleProducer(new Factory<T>(), normalizeBufferSize(bufferSize), timeoutBlockingWaitStrategy);
        } else {
            ringBuffer = RingBuffer.createMultiProducer(new Factory<T>(), normalizeBufferSize(bufferSize), timeoutBlockingWaitStrategy);
        }
        consumedSeq = new Sequence();
        ringBuffer.addGatingSequences(consumedSeq);
        barrier = ringBuffer.newBarrier();
        long cursor = ringBuffer.getCursor();
        consumedSeq.set(cursor);
        knownPublishedSeq = cursor;
    }

    @Override
    public int drainTo(Collection<? super T> collection) {
        return drainTo(collection, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        long pos = consumedSeq.get() + 1;
        if (pos + maxElements - 1 > knownPublishedSeq) {
            updatePublishedSequence();
        }
        int c = 0;
        try {
            while (pos <= knownPublishedSeq && c <= maxElements) {
                Event<T> eventHolder = ringBuffer.get(pos);
                collection.add(eventHolder.removeValue());
                c++;
                pos++;
            }
        } finally {
            if (c > 0) {
                consumedSeq.addAndGet(c);
            }
        }
        return c;
    }

    @Override
    public Iterator<T> iterator() {
        throw new UnsupportedOperationException();
    }

    private int normalizeBufferSize(int bufferSize) {
        if (bufferSize <= 0) {
            return 8192;
        }
        int ringBufferSize = 2;
        while (ringBufferSize < bufferSize) {
            ringBufferSize *= 2;
        }
        return ringBufferSize;
    }

    @Override
    public boolean offer(T e) {
        long seq;
        try {
            seq = ringBuffer.tryNext();
        } catch (InsufficientCapacityException e1) {
            return false;
        }
        publish(e, seq);
        return true;
    }

    @Override
    public boolean offer(T e, long timeout, TimeUnit unit) throws InterruptedException {
        final long timeoutInNanos = unit.toNanos(timeout);
        final long deadline = System.nanoTime() + timeoutInNanos;
        long seq = -1;
        while (System.nanoTime() <= deadline) {
            try {
                seq = ringBuffer.tryNext();
                break;
            } catch (InsufficientCapacityException e1) {
                ThreadHints.onSpinWait();
                continue;
            }
        }
        if (seq >= 0) {
            publish(e, seq);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public T peek() {
        long l = consumedSeq.get() + 1;
        if (l > knownPublishedSeq) {
            updatePublishedSequence();
        }
        if (l <= knownPublishedSeq) {
            Event<T> eventHolder = ringBuffer.get(l);
            return eventHolder.readValue();
        }
        return null;
    }

    @Override
    public T poll() {
        long l = consumedSeq.get() + 1;
        if (l > knownPublishedSeq) {
            updatePublishedSequence();
        }
        if (l <= knownPublishedSeq) {
            return processElement(l);
        }
        return null;
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            this.timeoutBlockingWaitStrategy.setTimeout(timeout, unit);
            long l = consumedSeq.get() + 1;
            while (knownPublishedSeq < l) {
                try {
                    knownPublishedSeq = barrier.waitFor(l);
                } catch (AlertException e) {
                    throw new InterruptedException(e.getMessage());
                } catch (TimeoutException e) {
                    return null;
                }
            }
            return processElement(l);
        } finally {
            this.timeoutBlockingWaitStrategy.resetTimeOut();
        }
    }

    @Override
    public void put(T e) throws InterruptedException {
        long seq = ringBuffer.next();
        publish(e, seq);
    }

    @Override
    public int remainingCapacity() {
        return ringBuffer.getBufferSize() - size();
    }

    @Override
    public int size() {
        return (int)(ringBuffer.getCursor() - consumedSeq.get());
    }

    @Override
    public T take() throws InterruptedException {
        long l = consumedSeq.get() + 1;
        this.timeoutBlockingWaitStrategy.resetTimeOut();
        while (knownPublishedSeq < l) {
            try {
                knownPublishedSeq = barrier.waitFor(l);
            } catch (AlertException e) {
                throw new InterruptedException(e.getMessage());
            } catch (TimeoutException e) {
                throw new InterruptedException(e.getMessage());
            }
        }
        return processElement(l);
    }

    @Override
    public String toString() {
        return "Cursor: " + ringBuffer.getCursor() + ", Consumerd :" + consumedSeq.get();
    }

    private void publish(T e, long seq) {
        Event<T> holder = ringBuffer.get(seq);
        holder.setValue(e);
        ringBuffer.publish(seq);
    }

    private T processElement(long sequence) {
        Event<T> eventHolder = ringBuffer.get(sequence);
        T t = eventHolder.removeValue();
        consumedSeq.incrementAndGet();
        return t;
    }

    private void updatePublishedSequence() {
        long c = ringBuffer.getCursor();
        if (c >= knownPublishedSeq + 1) {
            long pos = c;
            for (long sequence = knownPublishedSeq + 1; sequence <= c; sequence++) {
                if (!ringBuffer.isPublished(sequence)) {
                    pos = sequence - 1;
                    break;
                }
            }
            knownPublishedSeq = pos;
        }
    }

    static class HybridTimeoutBlockingWaitStrategy implements WaitStrategy {
        private static final int SPIN_TRIES = 1000;
        private final Lock lock = new ReentrantLock();
        private final Condition processorNotifyCondition = lock.newCondition();
        private final AtomicBoolean signalNeeded = new AtomicBoolean(false);
        private long timeoutInNanos = -1;

        public void setTimeout(final long timeout, final TimeUnit units) {
            this.timeoutInNanos = units.toNanos(timeout);
        }

        public void resetTimeOut() {
            this.timeoutInNanos = -1;
        }

        @Override
        public long waitFor(long sequence, Sequence cursor, Sequence dependentSequence, SequenceBarrier barrier)
            throws AlertException, InterruptedException, TimeoutException {
            long availableSequence;
            int counter = SPIN_TRIES;
            do {
                // 1: spin
                if ((availableSequence = dependentSequence.get()) >= sequence) {
                    return availableSequence;
                }
                // 2: Object Lock
                if (0 == --counter) {
                    lock.lock();
                    try {
                        while ((availableSequence = cursor.get()) < sequence) {
                            barrier.checkAlert();
                            signalNeeded.getAndSet(true);
                            if (timeoutInNanos > 0) {
                                if (processorNotifyCondition.awaitNanos(timeoutInNanos) <= 0) {
                                    throw TimeoutException.INSTANCE;
                                }
                            } else {
                                processorNotifyCondition.await();
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                    while ((availableSequence = dependentSequence.get()) < sequence) {
                        barrier.checkAlert();
                    }
                    counter = SPIN_TRIES;
                    return availableSequence;
                }
            } while (true);
        }

        @Override
        public void signalAllWhenBlocking() {
            if (signalNeeded.getAndSet(false)) {
                lock.lock();
                try {
                    processorNotifyCondition.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }

        @Override
        public String toString() {
            return "HybridTimeoutBlockingWaitStrategy{" + "signalNeeded=" + signalNeeded.get() + '}';
        }
    }

}
