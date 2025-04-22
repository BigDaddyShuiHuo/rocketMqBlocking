package cn.hwz.learn.mqblocking.defaultMq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragelyByCircle;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.consumer.ConsumeMessageService;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.ProcessQueue;
import org.apache.rocketmq.client.impl.consumer.RebalanceImpl;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang3.reflect.MethodUtils.invokeMethod;

/**
 * @author needleHuo
 * @version jdk11
 * @description 临时消费者，用于解决阻塞问题S
 */
@Slf4j(topic = "c.DefaultConsumerMain")
public class TempConsumerMain {
    public static void main(String[] args) throws MQClientException, NoSuchFieldException, InterruptedException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        // 拉取模式
        DefaultMQPushConsumer consumer = createTempConsumer();

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {

                String message = new String(list.get(0).getBody());
                /**
                 * 模拟消费。可以先保存到数据库或者转发到另外一个topic，先迅速降低队列中的，消息数，防止堵住
                 * 这里直接用打印来模拟
                 */
                log.debug("当前线程:{}，接受到的消息:{}",Thread.currentThread().getName(),message);

                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
        log.debug("消费者启动完成......");
        // 低于500条就关闭
        monitorShutDown(consumer,500);

    }

    /**
     * 创建临时消费者
     * @return
     * @throws MQClientException
     */
    public static DefaultMQPushConsumer createTempConsumer() throws MQClientException {
        // 拉取模式
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("myConsumer");
        consumer.setNamesrvAddr("192.168.244.128:9876");
        consumer.subscribe("dfTopic", "*");

        // 多拉几条
        consumer.setConsumeMessageBatchMaxSize(32);
        // 扩大4倍，加快消费
        consumer.setConsumeThreadMin(4);
        // 救急线程设置多一些
        consumer.setConsumeThreadMax(8);
        // 调整分配策略
        consumer.setAllocateMessageQueueStrategy(new AllocateMessageQueueAveragelyByCircle());
        return consumer;
    }

    public static void monitorShutDown(DefaultMQPushConsumer consumer,long threshold){
        long start =System.currentTimeMillis();
        // 单线程监控是否终止
        ScheduledExecutorService executore = Executors.newSingleThreadScheduledExecutor();
        executore.scheduleWithFixedDelay(()->{
            try {
                long surplus = getSurplus(consumer, "dfTopic");
                log.debug("剩余数量：{}",surplus);
                // 达到预期关闭值了
                if (surplus<threshold){
                    // 关闭超时时间先简单设置100秒
                    shutdown(consumer,100000);
                    log.debug("消费者启动关闭......{}",System.currentTimeMillis()-start);
                    executore.shutdown();
                }
            } catch (Exception e) {
                log.debug("出现异常{}",e.getMessage());
                throw new RuntimeException(e);
            }
        },1,1, TimeUnit.SECONDS);

    }

    /**
     * 停止消费者
     * @param consumer
     */
    public static void shutdown(DefaultMQPushConsumer consumer,long timeOut) throws NoSuchFieldException, InvocationTargetException, IllegalAccessException, NoSuchMethodException, InterruptedException {
        // 停止接收
        consumer.suspend();
        long start = System.currentTimeMillis();
        // 收尾工作，如果有还在进行中的消息，等他完成
        while(getProcessingMsCount(consumer)>0){
            // 需要等待的时间
            if (System.currentTimeMillis()-start>=timeOut){
                // 2. 获取所有消息队列
                Set<MessageQueue> allQueues = consumer.getDefaultMQPushConsumerImpl()
                        .getRebalanceImpl()
                        .getProcessQueueTable()
                        .keySet();
                // 持久化当前消费位置
                consumer.getDefaultMQPushConsumerImpl().getOffsetStore().persistAll(allQueues);
                break;
            }
            Thread.sleep(500);
        }
        consumer.shutdown();
    }

    /**
     * 获取正在处理中的消息数量
     * @param consumer
     * @return
     */
    public static int getProcessingMsCount(DefaultMQPushConsumer consumer) throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        // 获取rebalanceImpl
        DefaultMQPushConsumerImpl impl = consumer.getDefaultMQPushConsumerImpl();;
        RebalanceImpl rebalanceImpl = impl.getRebalanceImpl();

        // 获取队列映射表
        ConcurrentMap<MessageQueue, ProcessQueue> processQueueTable = rebalanceImpl.getProcessQueueTable();



        // 统计数量
        int total = 0;
        for (ProcessQueue queue: processQueueTable.values()){
            total = total+queue.getMsgCount().intValue();
        }
        return total;
    }

    /**
     * 获取Broker中剩下的消息
     * @param consumer
     * @return
     */
    public static long getSurplus(DefaultMQPushConsumer consumer,String topic) throws MQClientException {
        Set<MessageQueue> messageQueues = consumer.fetchSubscribeMessageQueues(topic);
        long totalOffset=0;
        for (MessageQueue queue:messageQueues){
            // 获取最新消息偏移量（最后接收的消息偏移量）
            long maxOffset = consumer.maxOffset(queue);

            // 从本地缓存获取消费位点
            long consumerOffset = consumer.getDefaultMQPushConsumerImpl()
                    .getOffsetStore()
                    .readOffset(queue, ReadOffsetType.MEMORY_FIRST_THEN_STORE);

            // 获取队列已消费的消息偏移量
            totalOffset = totalOffset+(maxOffset-consumerOffset);
        }
        return totalOffset;
    }
}
