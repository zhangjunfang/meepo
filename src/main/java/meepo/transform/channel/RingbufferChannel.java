package meepo.transform.channel;

import com.google.common.collect.Lists;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.ProducerType;
import meepo.transform.sink.AbstractSink;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by peiliping on 17-3-2.
 */
public class RingbufferChannel {

    private final RingBuffer<DataEvent> ringBuffer;

    private final SequenceBarrier seqBarrier;

    private final AtomicBoolean STARTED = new AtomicBoolean(false);

    private Sequence consumerSequence;

    public RingbufferChannel(int bufferSize, int sourcesCount, int tolerableDelaySeconds) {
        ProducerType producerType = sourcesCount > 1 ? ProducerType.MULTI : ProducerType.SINGLE;
        this.ringBuffer = RingBuffer.create(producerType, DataEvent.INT_ENEVT_FACTORY, bufferSize,
                new TimeoutBlockingWaitStrategy(tolerableDelaySeconds, TimeUnit.SECONDS));//LiteTimeoutBlockingWaitStrategy(tolerableDelaySeconds, TimeUnit.SECONDS)
        this.seqBarrier = this.ringBuffer.newBarrier();
    }

    public List<EventProcessor> start(AbstractSink... handlers) {
        checkRunning();
        List<EventProcessor> wps = Lists.newArrayList();
        if (handlers.length == 1) {
            BatchEventProcessor<DataEvent> processor = new BatchEventProcessor<>(this.ringBuffer, this.seqBarrier, handlers[0]);
            this.consumerSequence = processor.getSequence();
            this.ringBuffer.addGatingSequences(this.consumerSequence);
            wps.add(processor);
        } else {
            this.consumerSequence = new Sequence(-1);
            for (AbstractSink wh : handlers) {
                WorkProcessor<DataEvent> processor = new WorkProcessor<>(this.ringBuffer, this.seqBarrier, wh, new IgnoreExceptionHandler(), this.consumerSequence);
                this.ringBuffer.addGatingSequences(processor.getSequence());
                wps.add(processor);
            }
        }
        return wps;
    }

    private void checkRunning() {
        Validate.isTrue(!this.STARTED.get());
        this.STARTED.set(true);
    }

    public long getNextSeq() {
        return this.ringBuffer.next();
    }

    public DataEvent getBySeq(long seq) {
        return this.ringBuffer.get(seq);
    }

    public void pushBySeq(long seq) {
        this.ringBuffer.publish(seq);
    }

    public boolean isEmpty() {
        return this.ringBuffer.remainingCapacity() == this.ringBuffer.getBufferSize();
    }

}