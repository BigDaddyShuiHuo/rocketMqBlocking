package cn.hwz.learn.mqblocking.defaultMq;

import cn.hwz.learn.mqblocking.tool.ThreadSleep;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragelyByCircle;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;

/**
 * @author needleHuo
 * @version jdk11
 * @description 同步消息消费者
 */
@Slf4j(topic = "c.DefaultConsumerMain")
public class DefaultConsumerMain {
    public static void main(String[] args) throws MQClientException {
        // 拉取模式
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("myConsumer");
        consumer.setNamesrvAddr("192.168.244.128:9876");
        consumer.subscribe("dfTopic", "*");

        // 每次就拉一条
        consumer.setConsumeMessageBatchMaxSize(1);
        // 一个线程去消费
        consumer.setConsumeThreadMin(1);
        // 没有救急线程
        consumer.setConsumeThreadMax(1);

        // 调整分配策略，默认是平均分配，把执行权分配出去，就是另外一个消费者执行完了，由于没有执行权
        // 也不会消费队列里的剩余消息
        consumer.setAllocateMessageQueueStrategy(new AllocateMessageQueueAveragelyByCircle());

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {

                String message = new String(list.get(0).getBody());

                log.debug("当前线程:{}，接受到的消息:{}",Thread.currentThread().getName(),message);
                // 故意消费得慢些
                ThreadSleep.sleep(5);
                // 默认返回成功
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        log.debug("消费者启动完成......");
    }
}
